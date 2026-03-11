package com.linux.ai.serverassistant.service.command;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.UserPrincipal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class OffloadExecutionSupport {

    private static final long OFFLOAD_COPY_TIMEOUT_SECONDS = 1800L;

    @FunctionalInterface
    interface OffloadCommandRunner {
        OffloadCommandResult run(List<String> command, long timeoutSeconds);
    }

    record OffloadCommandResult(boolean success, String output) {}

    private OffloadExecutionSupport() {}

    static String executeOffload(Path source, Path targetRoot) {
        return executeOffload(source, targetRoot, OffloadExecutionSupport::runCommand);
    }

    static String executeOffload(Path source, Path targetRoot, OffloadCommandRunner commandRunner) {
        if (source == null || targetRoot == null) {
            return "❌ Offload 中止：來源或目標路徑為空。";
        }
        if (commandRunner == null) {
            return "❌ Offload 中止：命令執行器未初始化。";
        }

        Path normalizedSource = source.normalize();
        Path normalizedTargetRoot = targetRoot.normalize();
        if (normalizedSource.getFileName() == null) {
            return "❌ Offload 中止：來源路徑不合法（請指定具體資料夾）。";
        }
        Path destination = normalizedTargetRoot.resolve(normalizedSource.getFileName()).normalize();

        String preflightError = validateOffloadRuntimePaths(normalizedSource, normalizedTargetRoot, destination);
        if (preflightError != null) {
            return preflightError;
        }

        String reserveError = reserveDestinationDirectory(destination);
        if (reserveError != null) {
            return reserveError;
        }

        OffloadCommandResult copyResult = runOffloadCopy(normalizedSource, destination, commandRunner);
        if (!copyResult.success()) {
            String cleanupError = deleteDirectoryRecursively(destination);
            String rawOutput = copyResult.output();
            String detail = (rawOutput == null || rawOutput.isBlank()) ? "(無額外輸出)" : rawOutput;
            String cleanupNote = cleanupError == null
                    ? ""
                    : "\n- 清理中間目標失敗：`%s`".formatted(cleanupError);
            return """
                    ❌ Offload 中止：複製失敗，未進行後續操作。

                    ```text
                    %s
                    ```
                    %s
                    """.formatted(detail, cleanupNote).trim();
        }

        if (!Files.exists(destination) || !Files.isDirectory(destination) || Files.isSymbolicLink(destination)) {
            return "❌ Offload 中止：複製看似完成，但找不到複製結果 `%s`。".formatted(destination);
        }

        String deleteError = deleteDirectoryRecursively(normalizedSource);
        if (deleteError != null) {
            return """
                    ❌ Offload 中止：複製已完成，但刪除舊資料夾失敗。
                    - 來源：`%s`
                    - 目標：`%s`
                    - 錯誤：`%s`
                    """.formatted(normalizedSource, destination, deleteError).trim();
        }

        try {
            Files.createSymbolicLink(normalizedSource, destination);
        } catch (Exception e) {
            String rollback = tryRestoreSourceWithRsync(destination, normalizedSource, commandRunner);
            return """
                    ❌ Offload 中止：複製已完成，但建立 symlink 失敗。
                    - symlink：`%s -> %s`
                    - 錯誤：`%s`

                    %s
                    """.formatted(normalizedSource, destination, e.getMessage(), rollback).trim();
        }

        if (!Files.isSymbolicLink(normalizedSource)) {
            return "❌ Offload 中止：symlink 建立後驗證失敗，請手動檢查 `%s`。".formatted(normalizedSource);
        }

        return """
                ✅ Offload 完成
                - 已複製：`%s` -> `%s`
                - 已建立 symlink：`%s` -> `%s`
                """.formatted(normalizedSource, destination, normalizedSource, destination).trim();
    }

    static List<Path> detectSubMountPoints(Path source) {
        if (source == null) return List.of();
        Path normalizedSource = source.normalize();
        if (!normalizedSource.isAbsolute()) return List.of();

        Path mountInfo = Paths.get("/proc/self/mountinfo");
        if (!Files.exists(mountInfo)) return List.of();

        try {
            List<String> lines = Files.readAllLines(mountInfo, StandardCharsets.UTF_8);
            return extractSubMountPointsFromMountInfo(normalizedSource, lines);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    static List<Path> extractSubMountPointsFromMountInfo(Path source, List<String> mountInfoLines) {
        if (source == null || mountInfoLines == null || mountInfoLines.isEmpty()) {
            return List.of();
        }

        Path normalizedSource = source.normalize();
        String sourceText = normalizedSource.toString();
        String sourcePrefix = sourceText.endsWith("/") ? sourceText : sourceText + "/";
        LinkedHashSet<Path> mounts = new LinkedHashSet<>();

        for (String line : mountInfoLines) {
            if (line == null || line.isBlank()) continue;

            String[] cols = line.split("\\s+");
            if (cols.length < 5) continue;

            String mountPointToken = decodeMountInfoPathToken(cols[4]);
            Path mountPoint;
            try {
                mountPoint = Paths.get(mountPointToken).normalize();
            } catch (Exception ignored) {
                continue;
            }
            if (!mountPoint.isAbsolute()) continue;

            String mountText = mountPoint.toString();
            if (mountText.equals(sourceText)) continue;
            if ("/".equals(sourceText)) {
                mounts.add(mountPoint);
                continue;
            }
            if (!mountText.startsWith(sourcePrefix)) continue;
            mounts.add(mountPoint);
        }

        return mounts.stream()
                .sorted(Comparator.comparing(Path::toString))
                .toList();
    }

    static String formatSubMountBlockedMessage(Path source, List<Path> subMounts, int hintLimit) {
        int limit = Math.min(hintLimit, subMounts.size());
        StringBuilder sb = new StringBuilder();
        sb.append("安全限制：來源資料夾內含子掛載點，為避免跨檔案系統複製與誤刪，已阻止 `/offload`。\n");
        sb.append("- 來源：`").append(source).append("`\n");
        sb.append("- 子掛載點（顯示前 ").append(limit).append(" 筆）：\n");
        for (int i = 0; i < limit; i++) {
            sb.append("  - `").append(subMounts.get(i)).append("`\n");
        }
        if (subMounts.size() > limit) {
            sb.append("- 其餘 ").append(subMounts.size() - limit).append(" 筆已省略。\n");
        }
        sb.append("- 請先卸載或移出子掛載點後再重試。");
        return sb.toString().trim();
    }

    static boolean isRootOwned(Path source) throws IOException {
        UserPrincipal owner = Files.getOwner(source);
        String ownerName = owner == null ? "" : owner.getName();
        if (ownerName == null) return false;
        String lower = ownerName.trim().toLowerCase(Locale.ROOT);
        return lower.equals("root") || lower.endsWith(":root") || lower.endsWith("\\root");
    }

    private static String validateOffloadRuntimePaths(Path source, Path targetRoot, Path destination) {
        try {
            if (!Files.exists(source)) return "❌ Offload 中止：來源不存在：`%s`".formatted(source);
            if (!Files.isDirectory(source)) return "❌ Offload 中止：來源不是資料夾：`%s`".formatted(source);
            if (Files.isSymbolicLink(source)) return "❌ Offload 中止：來源是 symlink，已拒絕操作：`%s`".formatted(source);

            if (!Files.exists(targetRoot)) return "❌ Offload 中止：目標磁碟路徑不存在：`%s`".formatted(targetRoot);
            if (!Files.isDirectory(targetRoot)) return "❌ Offload 中止：目標磁碟路徑不是資料夾：`%s`".formatted(targetRoot);
            if (Files.isSymbolicLink(targetRoot)) return "❌ Offload 中止：目標磁碟路徑不可為 symlink：`%s`".formatted(targetRoot);

            if (targetRoot.startsWith(source)) return "❌ Offload 中止：目標路徑不能在來源資料夾內，避免遞迴複製。";

            Path sourceReal = source.toRealPath();
            Path targetRootReal = targetRoot.toRealPath();
            if (targetRootReal.startsWith(sourceReal)) {
                return "❌ Offload 中止：目標路徑解析後位於來源資料夾內，避免遞迴複製。";
            }
        } catch (Exception e) {
            return "❌ Offload 中止：執行前路徑檢查失敗：" + e.getMessage();
        }

        if (destination.equals(source)) {
            return "❌ Offload 中止：來源與目標不能相同。";
        }
        if (Files.exists(destination)) {
            return "❌ Offload 中止：目標位置已存在同名資料夾：`%s`（請先手動處理）".formatted(destination);
        }
        return null;
    }

    private static String reserveDestinationDirectory(Path destination) {
        try {
            Files.createDirectory(destination);
            return null;
        } catch (IOException e) {
            return "❌ Offload 中止：無法保留目標路徑 `%s`：`%s`".formatted(destination, e.getMessage());
        }
    }

    private static String decodeMountInfoPathToken(String token) {
        if (token == null || token.isEmpty()) return token;
        StringBuilder sb = new StringBuilder(token.length());
        int len = token.length();
        for (int i = 0; i < len; i++) {
            char c = token.charAt(i);
            if (c == '\\' && i + 3 < len
                    && isOctalDigit(token.charAt(i + 1))
                    && isOctalDigit(token.charAt(i + 2))
                    && isOctalDigit(token.charAt(i + 3))) {
                int value = (token.charAt(i + 1) - '0') * 64
                        + (token.charAt(i + 2) - '0') * 8
                        + (token.charAt(i + 3) - '0');
                sb.append((char) value);
                i += 3;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isOctalDigit(char c) {
        return c >= '0' && c <= '7';
    }

    private static OffloadCommandResult runCommand(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(1, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
                return new OffloadCommandResult(false, "命令逾時（" + timeoutSeconds + " 秒）：" + String.join(" ", command));
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exit = process.exitValue();
            if (exit != 0) {
                String msg = output.isBlank() ? ("命令失敗（Exit Code: " + exit + "）") : output;
                return new OffloadCommandResult(false, msg);
            }
            return new OffloadCommandResult(true, output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new OffloadCommandResult(false, "命令執行被中斷");
        } catch (IOException e) {
            return new OffloadCommandResult(false, e.getMessage());
        } finally {
            if (process != null) {
                try { process.getInputStream().close(); } catch (IOException ignored) {}
                try { process.getErrorStream().close(); } catch (IOException ignored) {}
                try { process.getOutputStream().close(); } catch (IOException ignored) {}
            }
        }
    }

    private static String deleteDirectoryRecursively(Path directory) {
        if (directory == null) return "路徑為空";
        try (var walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
            return null;
        } catch (Exception e) {
            return e.getMessage();
        }
    }

    private static String tryRestoreSourceWithRsync(
            Path destination,
            Path source,
            OffloadCommandRunner commandRunner) {
        OffloadCommandResult rollback = commandRunner.run(
                List.of("rsync", "-a", "--", destination.toString() + "/", source.toString()),
                OFFLOAD_COPY_TIMEOUT_SECONDS);
        if (rollback.success() && Files.exists(source) && Files.isDirectory(source)) {
            return "已嘗試 rollback：已用 `rsync` 將資料複製回原路徑。";
        }
        if (rollback.output() == null || rollback.output().isBlank()) {
            return "rollback 失敗：無法將資料複製回原路徑。";
        }
        return "rollback 失敗：" + rollback.output();
    }

    private static OffloadCommandResult runOffloadCopy(
            Path source,
            Path destination,
            OffloadCommandRunner commandRunner) {
        OffloadCommandResult rsyncResult = commandRunner.run(
                List.of("rsync", "-a", "--", source.toString() + "/", destination.toString() + "/"),
                OFFLOAD_COPY_TIMEOUT_SECONDS);
        if (rsyncResult.success()) {
            return rsyncResult;
        }

        if (isRsyncUnavailable(rsyncResult.output())) {
            return new OffloadCommandResult(false, "無 rsync");
        }

        return rsyncResult;
    }

    private static boolean isRsyncUnavailable(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase(Locale.ROOT);
        return lower.contains("cannot run program \"rsync\"")
                || lower.contains("cannot run program 'rsync'")
                || lower.contains("rsync: command not found")
                || lower.contains("command not found: rsync");
    }
}
