package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.AuditLogPersistenceService;
import com.linux.ai.serverassistant.service.command.CommandAuditService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

import static com.linux.ai.serverassistant.util.ToolResultUtils.formatExecutionResult;

@Service
public class DiskMountService {

    private static final Logger log = LoggerFactory.getLogger(DiskMountService.class);
    private static final Pattern FSTYPE_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]{1,32}$");
    private static final Pattern OPTIONS_PATTERN = Pattern.compile("^[a-zA-Z0-9,=._:-]{1,120}$");
    private static final Path DEFAULT_FSTAB_PATH = Path.of("/etc/fstab");
    private static final Object FSTAB_UPDATE_LOCK = new Object();
    private static final List<String> REQUIRED_COMMANDS = List.of("mkfs", "blkid", "mount", "findmnt", "lsblk");

    private final CommandLogRepository commandLogRepository;
    private final Path fstabPath;
    private final UserContext userContext;

    public record MountExecutionResult(String rawToolResult, boolean success, Integer exitCode) {}

    @Autowired
    public DiskMountService(CommandLogRepository commandLogRepository, UserContext userContext) {
        this(commandLogRepository, DEFAULT_FSTAB_PATH, userContext);
    }

    DiskMountService(CommandLogRepository commandLogRepository, Path fstabPath, UserContext userContext) {
        this.commandLogRepository = commandLogRepository;
        this.fstabPath = (fstabPath == null) ? DEFAULT_FSTAB_PATH : fstabPath;
        this.userContext = userContext;
    }

    public String executeConfirmedMount(Path device, Path target, String fstype, String options) {
        return executeConfirmedMountWithResult(device, target, fstype, options).rawToolResult();
    }

    public MountExecutionResult executeConfirmedMountWithResult(Path device, Path target, String fstype, String options) {
        if (device == null || target == null) {
            return failed(toolResult(CommandMarkers.SECURITY_VIOLATION + " 掛載參數不能為空"), null);
        }
        Path normalizedDevice = device.normalize();
        Path normalizedTarget = target.normalize();
        String safeFsType = (fstype == null || fstype.isBlank()) ? "ext4" : fstype.trim();
        String safeOptions = (options == null || options.isBlank()) ? "defaults,nofail" : options.trim();
        String commandText = "mount " + normalizedDevice + " " + normalizedTarget + " " + safeFsType + " " + safeOptions;

        String validationError = validateMountInput(normalizedDevice, normalizedTarget, safeFsType, safeOptions);
        if (validationError != null) {
            String content = CommandMarkers.SECURITY_VIOLATION + " " + validationError;
            saveAuditLog(commandText, content, false);
            return failed(toolResult(content), null);
        }

        List<String> summary = new ArrayList<>();
        summary.add("目標掛載點：`" + normalizedTarget + "`");
        summary.add("檔案系統：`" + safeFsType + "`");
        summary.add("fstab options：`" + safeOptions + "`");

        try {
            Files.createDirectories(normalizedTarget);
            summary.add("已確認/建立掛載點目錄。");
        } catch (Exception e) {
            String content = "❌ mount 失敗：建立掛載點目錄失敗 - " + e.getMessage();
            saveAuditLog(commandText, content, false);
            return failed(toolResult(content), null);
        }

        CommandRunResult mkfs = runFixedCommand(
                List.of("mkfs", "-t", safeFsType, normalizedDevice.toString()),
                180
        );
        if (mkfs.exitCode() != 0) {
            String detail = mkfs.output() == null || mkfs.output().isBlank()
                    ? "mkfs 失敗（Exit Code: " + mkfs.exitCode() + "）"
                    : mkfs.output().trim();
            String content = "❌ mount 失敗：格式化硬碟失敗。\n\n" + detail;
            saveAuditLog(commandText, content, false);
            return failed(toolResult(content), mkfs.exitCode());
        }
        summary.add("已完成格式化。");

        String uuid = resolveUuid(normalizedDevice);
        if (uuid == null || uuid.isBlank()) {
            String content = "❌ mount 失敗：無法透過 `blkid` 取得 UUID。";
            saveAuditLog(commandText, content, false);
            return failed(toolResult(content), null);
        }
        summary.add("UUID：`" + uuid + "`");

        FstabUpdateResult fstabUpdate;
        try {
            fstabUpdate = upsertFstabEntry(normalizedTarget, uuid, safeFsType, safeOptions);
            summary.add("已備份 fstab：`" + fstabUpdate.backupPath() + "`");
            summary.add("已寫入 fstab：`" + fstabUpdate.entry() + "`");
        } catch (Exception e) {
            String content = "❌ mount 失敗：更新 `/etc/fstab` 失敗 - " + e.getMessage();
            saveAuditLog(commandText, content, false);
            return failed(toolResult(content), null);
        }

        CommandRunResult mountRun = runFixedCommand(List.of("mount", normalizedTarget.toString()), 30);
        if (mountRun.exitCode() != 0) {
            String detail = mountRun.output() == null || mountRun.output().isBlank()
                    ? "mount 失敗（Exit Code: " + mountRun.exitCode() + "）"
                    : mountRun.output().trim();
            boolean rollbackOk = restoreFstabFromBackup(fstabUpdate.backupPath());
            String rollbackStatus = rollbackOk
                    ? "已自動回滾 `/etc/fstab` 至備份版本。"
                    : "⚠️ 自動回滾 `/etc/fstab` 失敗，請手動使用備份檔還原。";
            String content = """
                    ❌ mount 失敗：已更新 fstab，但掛載失敗。
                    - fstab 備份：`%s`
                    - fstab 條目：`%s`
                    - 回滾狀態：%s

                    %s
                    """.formatted(fstabUpdate.backupPath(), fstabUpdate.entry(), rollbackStatus, detail).trim();
            saveAuditLog(commandText, content, false);
            return failed(toolResult(content), mountRun.exitCode());
        }

        CommandRunResult snapshot = runFixedCommand(
                List.of("findmnt", "-no", "SOURCE,FSTYPE,OPTIONS", "--target", normalizedTarget.toString()),
                5
        );
        if (snapshot.exitCode() == 0 && snapshot.output() != null && !snapshot.output().isBlank()) {
            summary.add("目前掛載資訊：`" + snapshot.output().trim() + "`");
        } else {
            summary.add("已掛載，但無法取得 `findmnt` 詳細資訊。");
        }

        String content = "✅ mount 完成\n- " + String.join("\n- ", summary);
        saveAuditLog(commandText, content, true);
        return success(toolResult(content));
    }

    private String validateMountInput(Path device, Path target, String fstype, String options) {
        if (!device.isAbsolute() || !target.isAbsolute()) {
            return "device 與 target 必須是絕對路徑";
        }
        String preflightError = validateMountPrerequisites();
        if (preflightError != null) {
            return preflightError;
        }
        if (!device.startsWith(Path.of("/dev"))) {
            return "device 必須位於 /dev 下";
        }
        if ("/".equals(target.toString())) {
            return "不允許掛載到 /";
        }
        if (!Files.exists(device)) {
            return "裝置不存在：" + device;
        }
        if (!isBlockDevice(device)) {
            return "device 不是 block device：" + device;
        }
        String deviceType = resolveBlockDeviceType(device);
        String deviceTypeError = validateDiskDeviceKind(device, deviceType, "`/mount`");
        if (deviceTypeError != null) {
            return deviceTypeError;
        }
        String mountedChildCheckError = validateMountedChildCheck(device);
        if (mountedChildCheckError != null) {
            return mountedChildCheckError;
        }
        if (Files.isSymbolicLink(target)) {
            return "安全限制：掛載點不可為 symbolic link：" + target;
        }
        if (Files.exists(target)) {
            if (!Files.isDirectory(target)) {
                return "掛載點存在但不是資料夾：" + target;
            }
        }
        if (!FSTYPE_PATTERN.matcher(fstype).matches()) {
            return "fstype 格式錯誤";
        }
        if (!OPTIONS_PATTERN.matcher(options).matches()) {
            return "options 格式錯誤";
        }

        String mountedSourceError = validateNotMountedBySource(device);
        if (mountedSourceError != null) {
            return mountedSourceError;
        }
        String mountedTargetError = validateNotMountedByTarget(target);
        if (mountedTargetError != null) {
            return mountedTargetError;
        }
        return null;
    }

    String validateMountPrerequisites() {
        for (String command : REQUIRED_COMMANDS) {
            if (!commandExists(command)) {
                return "安全限制：缺少必要系統指令 `" + command + "`，已拒絕執行。";
            }
        }
        if (!Files.exists(fstabPath)) {
            return "安全限制：`/etc/fstab` 不存在，已拒絕執行。";
        }
        if (!Files.isRegularFile(fstabPath)) {
            return "安全限制：`/etc/fstab` 不是一般檔案，已拒絕執行。";
        }
        if (!Files.isReadable(fstabPath) || !Files.isWritable(fstabPath)) {
            return "安全限制：`/etc/fstab` 不可讀寫，已拒絕執行。";
        }
        return null;
    }

    private boolean commandExists(String command) {
        if (command == null || command.isBlank()) return false;
        String pathValue = System.getenv("PATH");
        if (pathValue == null || pathValue.isBlank()) return false;
        String[] dirs = pathValue.split(":");
        for (String dir : dirs) {
            if (dir == null || dir.isBlank()) continue;
            Path candidate = Path.of(dir, command);
            if (Files.isExecutable(candidate) && !Files.isDirectory(candidate)) {
                return true;
            }
        }
        return false;
    }

    private CommandRunResult runFixedCommand(List<String> command, long timeoutSeconds) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandRunResult(124, "命令逾時（" + timeoutSeconds + " 秒）");
            }

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().reduce((a, b) -> a + "\n" + b).orElse("");
            }
            return new CommandRunResult(process.exitValue(), output);
        } catch (Exception e) {
            return new CommandRunResult(1, e.getMessage() == null ? "未知錯誤" : e.getMessage());
        }
    }

    private String toolResult(String content) {
        return formatExecutionResult(content);
    }

    private MountExecutionResult success(String rawToolResult) {
        return new MountExecutionResult(rawToolResult, true, 0);
    }

    private MountExecutionResult failed(String rawToolResult, Integer exitCode) {
        return new MountExecutionResult(rawToolResult, false, exitCode);
    }

    private String resolveUuid(Path device) {
        CommandRunResult blkid = runFixedCommand(
                List.of("blkid", "-s", "UUID", "-o", "value", device.toString()),
                10
        );
        if (blkid.exitCode() != 0) return null;
        if (blkid.output() == null) return null;
        String out = blkid.output().trim();
        if (out.isBlank()) return null;
        String[] lines = out.split("\\R");
        return lines.length == 0 ? null : lines[0].trim();
    }

    private String validateMountedChildCheck(Path device) {
        CommandRunResult result = runFixedCommand(List.of("lsblk", "-nr", "-o", "PATH,MOUNTPOINT", device.toString()), 5);
        return validateMountedChildCheckResult(device, result.exitCode(), result.output());
    }

    String validateMountedChildCheckResult(Path device, int exitCode, String output) {
        if (device == null) return "device 不能為空";
        if (exitCode != 0) {
            return "安全限制：無法檢查裝置子分割區掛載狀態（lsblk 執行失敗），已拒絕執行。";
        }
        if (output == null || output.isBlank()) {
            return "安全限制：無法檢查裝置子分割區掛載狀態（lsblk 無輸出），已拒絕執行。";
        }
        String mountedChild = findMountedChildFromLsblkOutput(device, output);
        if (mountedChild != null) {
            return "裝置子分割區目前已掛載，請先卸載後再格式化整顆磁碟：" + mountedChild;
        }
        return null;
    }

    private String validateNotMountedBySource(Path device) {
        CommandRunResult result = runFixedCommand(
                List.of("findmnt", "-rn", "--source", device.toString()),
                5
        );
        return validateFindmntProbeResult(
                result.exitCode(),
                result.output(),
                "裝置目前已掛載，為避免格式化運作中磁碟，請先卸載：" + device,
                "安全限制：無法檢查裝置掛載狀態（findmnt 執行失敗），已拒絕執行。"
        );
    }

    private String validateNotMountedByTarget(Path target) {
        CommandRunResult result = runFixedCommand(
                List.of("findmnt", "-rn", "--target", target.toString()),
                5
        );
        return validateFindmntProbeResult(
                result.exitCode(),
                result.output(),
                "目標掛載點目前已掛載，請先卸載：" + target,
                "安全限制：無法檢查目標掛載點狀態（findmnt 執行失敗），已拒絕執行。"
        );
    }

    String validateFindmntProbeResult(int exitCode, String output, String mountedError, String probeFailureError) {
        String out = output == null ? "" : output.trim();
        if (!out.isBlank()) {
            return mountedError;
        }
        if (exitCode == 0 || exitCode == 1) {
            return null;
        }
        return probeFailureError + "（Exit Code: " + exitCode + "）";
    }

    private boolean isBlockDevice(Path device) {
        try {
            Object modeObj = Files.getAttribute(device, "unix:mode");
            if (modeObj instanceof Number number) {
                int mode = number.intValue();
                return (mode & 0170000) == 0060000;
            }
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            // Fallback below
        } catch (Exception e) {
            return false;
        }
        CommandRunResult fallback = runFixedCommand(List.of("test", "-b", device.toString()), 5);
        return fallback.exitCode() == 0;
    }

    String validateDiskDeviceKind(Path device, String deviceType, String commandName) {
        if (device == null) return "device 不能為空";
        String command = (commandName == null || commandName.isBlank()) ? "`/mount`" : commandName.trim();
        String normalized = deviceType == null ? "" : deviceType.trim().toLowerCase();
        if (normalized.equals("disk")) {
            return null;
        }
        if (normalized.isBlank()) {
            return "安全限制：無法判斷裝置類型（僅允許整顆磁碟 TYPE=disk）：" + device;
        }
        return "安全限制：" + command + " 僅允許整顆磁碟（TYPE=disk），目前裝置類型為 `" + normalized + "`：" + device;
    }

    private String resolveBlockDeviceType(Path device) {
        CommandRunResult result = runFixedCommand(List.of("lsblk", "-dn", "-o", "TYPE", device.toString()), 5);
        if (result.exitCode() == 0 && result.output() != null) {
            String[] lines = result.output().trim().split("\\R");
            if (lines.length > 0) {
                String type = lines[0].trim().toLowerCase();
                if (!type.isBlank()) return type;
            }
        }
        return resolveBlockDeviceTypeFromSysfs(device);
    }

    private String resolveBlockDeviceTypeFromSysfs(Path device) {
        if (device == null || device.getFileName() == null) return null;
        String name = device.getFileName().toString();
        if (name.isBlank()) return null;

        if (name.startsWith("dm-") || device.normalize().toString().startsWith("/dev/mapper/")) {
            return "lvm";
        }

        Path sysBlock = Path.of("/sys/class/block", name);
        if (!Files.exists(sysBlock)) return null;
        if (Files.exists(sysBlock.resolve("partition"))) return "part";
        if (name.startsWith("loop")) return "loop";
        if (Files.exists(sysBlock.resolve("device"))) return "disk";
        return null;
    }

    String findMountedChildFromLsblkOutput(Path device, String output) {
        if (device == null || output == null || output.isBlank()) return null;
        String rootPath = device.normalize().toString();
        if (rootPath.isBlank()) return null;

        String[] lines = output.split("\\R");
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            String[] cols = line.trim().split("\\s+", 2);
            if (cols.length < 2) continue;
            String path = cols[0].trim();
            String mountPoint = cols[1].trim();
            if (path.isBlank() || mountPoint.isBlank() || mountPoint.equals("-")) continue;
            if (path.equals(rootPath)) continue;
            return path + " -> " + mountPoint;
        }
        return null;
    }

    FstabUpdateResult upsertFstabEntry(Path target, String uuid, String fstype, String options) throws Exception {
        synchronized (FSTAB_UPDATE_LOCK) {
            if (!Files.exists(fstabPath)) {
                throw new IllegalStateException("/etc/fstab 不存在");
            }

            List<String> originalLines = Files.readAllLines(fstabPath, StandardCharsets.UTF_8);
            String passNo = fstabPassNoForFsType(fstype);
            String entry = "UUID=" + uuid + " " + target + " " + fstype + " " + options + " 0 " + passNo;

            List<String> updated = new ArrayList<>();
            boolean inserted = false;
            for (String line : originalLines) {
                String current = line == null ? "" : line;
                String trimmed = current.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    updated.add(current);
                    continue;
                }

                String[] cols = trimmed.split("\\s+");
                if (cols.length >= 2) {
                    boolean sameUuid = cols[0].equals("UUID=" + uuid);
                    boolean sameTarget = cols[1].equals(target.toString());
                    if (sameUuid || sameTarget) {
                        if (!inserted) {
                            updated.add(entry);
                            inserted = true;
                        }
                        continue;
                    }
                }
                updated.add(current);
            }
            if (!inserted) {
                updated.add(entry);
            }

            Path backup = fstabPath.resolveSibling("fstab.bak.server-assistant." + System.currentTimeMillis());
            Files.copy(fstabPath, backup, StandardCopyOption.REPLACE_EXISTING);

            Path temp = fstabPath.resolveSibling("fstab.tmp.server-assistant." + System.nanoTime());
            try {
                Files.write(temp, updated, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                try {
                    Files.move(temp, fstabPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                    Files.move(temp, fstabPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception cleanupError) {
                    log.debug("Failed to delete temporary fstab file {}: {}", temp, cleanupError.getMessage());
                }
            }

            return new FstabUpdateResult(backup, entry);
        }
    }

    String fstabPassNoForFsType(String fstype) {
        if (fstype == null) return "0";
        String normalized = fstype.trim().toLowerCase();
        if (normalized.equals("ext2") || normalized.equals("ext3") || normalized.equals("ext4")) {
            return "2";
        }
        return "0";
    }

    boolean restoreFstabFromBackup(Path backupPath) {
        if (backupPath == null || !Files.exists(backupPath)) return false;
        synchronized (FSTAB_UPDATE_LOCK) {
            try {
                Files.copy(backupPath, fstabPath, StandardCopyOption.REPLACE_EXISTING);
                return true;
            } catch (Exception e) {
                log.warn("Failed to restore /etc/fstab from backup {}: {}", backupPath, e.getMessage());
                return false;
            }
        }
    }

    private void saveAuditLog(String command, String output, boolean success) {
        try {
            String currentUser = userContext.resolveUsernameOrAnonymous();
            if (currentUser == null || currentUser.isBlank() || "anonymous".equalsIgnoreCase(currentUser)) {
                currentUser = "system";
            }

            CommandLog cmdLog = new CommandLog();
            cmdLog.setUsername(currentUser);
            cmdLog.setCommand(command);
            cmdLog.setOutput(CommandAuditService.truncateOutput(output));
            cmdLog.setSuccess(success);
            cmdLog.setCommandType(CommandAuditService.classifyCommandType(command));
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "DiskMountService");
        } catch (Exception ex) {
            log.error("[Audit] Failed to save mount log: {}", ex.getMessage());
        }
    }

    static record FstabUpdateResult(Path backupPath, String entry) {}
    private record CommandRunResult(int exitCode, String output) {}
}
