package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static com.linux.ai.serverassistant.util.ToolResultUtils.extractToolResult;

@Service
public class OffloadJobService {

    private static final Logger log = LoggerFactory.getLogger(OffloadJobService.class);
    private static final long PROGRESS_POLL_INTERVAL_MS = 1_000L;
    private static final long FINISHED_JOB_RETENTION_MS = 2 * 60 * 60 * 1_000L;
    private static final int MAX_PARALLEL_OFFLOAD_JOBS = 2;

    private final CommandExecutionService commandExecutionService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final Map<String, OffloadJob> jobs = new ConcurrentHashMap<>();
    private final Set<String> activeSourceLocks = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newFixedThreadPool(MAX_PARALLEL_OFFLOAD_JOBS, r -> {
        Thread t = new Thread(r, "offload-job-worker");
        t.setDaemon(true);
        return t;
    });

    public OffloadJobService(CommandExecutionService commandExecutionService,
                             AdminAuthorizationService adminAuthorizationService) {
        this.commandExecutionService = commandExecutionService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    public String startOffloadJob(String username,
                                  Path source,
                                  Path targetRoot,
                                  Consumer<String> onFinished) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("使用者不可為空");
        }
        String actor = username.trim();
        boolean isAdmin = adminAuthorizationService.isAdmin(actor);
        if (!isAdmin) {
            throw new IllegalStateException("權限不足：`/offload` 僅限管理員操作。");
        }
        if (source == null || targetRoot == null) {
            throw new IllegalArgumentException("來源與目標路徑不可為空");
        }

        clearExpiredJobs();

        String jobId = UUID.randomUUID().toString();
        Path normalizedSource = source.normalize();
        Path normalizedTargetRoot = targetRoot.normalize();
        if (normalizedSource.getFileName() == null) {
            throw new IllegalArgumentException("來源路徑不合法（請指定具體資料夾）");
        }
        Path destination = normalizedTargetRoot.resolve(normalizedSource.getFileName()).normalize();
        String sourceLockKey = normalizedSource.toString();
        if (!activeSourceLocks.add(sourceLockKey)) {
            throw new IllegalStateException("已有進行中的 offload 任務在處理此來源路徑，請稍後再試。");
        }

        OffloadJob job = new OffloadJob(
                jobId,
                actor,
                normalizedSource,
                normalizedTargetRoot,
                destination,
                sourceLockKey
        );
        jobs.put(jobId, job);
        try {
            executor.submit(() -> runJob(job, onFinished));
        } catch (Exception e) {
            activeSourceLocks.remove(sourceLockKey);
            jobs.remove(jobId);
            throw e;
        }
        return jobId;
    }

    public Optional<OffloadProgressSnapshot> getJobProgress(String username, String jobId) {
        if (username == null || username.isBlank() || jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }

        clearExpiredJobs();
        OffloadJob job = jobs.get(jobId.trim());
        if (job == null) return Optional.empty();
        if (!job.username.equals(username.trim())) return Optional.empty();

        return Optional.of(toSnapshot(job));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void runJob(OffloadJob job, Consumer<String> onFinished) {
        job.state = "RUNNING";
        job.startedAt = System.currentTimeMillis();
        job.message = "計算總大小中...";
        job.totalBytes = safeDirectorySize(job.source);
        if (job.totalBytes < 0) {
            job.message = "複製中（總大小未知）...";
        } else {
            job.message = "複製中...";
        }

        AtomicBoolean running = new AtomicBoolean(true);
        Thread monitor = new Thread(() -> monitorProgress(job, running), "offload-progress-" + job.jobId);
        monitor.setDaemon(true);
        monitor.start();

        String result;
        try {
            result = OffloadExecutionSupport.executeOffload(job.source, job.targetRoot, (command, timeoutSeconds) ->
                    runCommandForOffloadJob(command, timeoutSeconds, job.username));
        } catch (Exception e) {
            log.warn("Offload job {} failed unexpectedly", job.jobId, e);
            String msg = e.getMessage() == null ? "未知錯誤" : e.getMessage();
            result = ("❌ Offload 中止：執行發生異常。\n\n```text\n" + msg + "\n```").trim();
        } finally {
            activeSourceLocks.remove(job.sourceLockKey);
            running.set(false);
            try {
                monitor.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        refreshCopied(job, true);
        boolean success = result != null && result.startsWith("✅ Offload 完成");
        if (success) {
            job.state = "SUCCEEDED";
            job.percent = 100;
            if (job.totalBytes > 0) {
                job.copiedBytes = Math.max(job.copiedBytes, job.totalBytes);
            }
            job.message = "Offload 完成";
        } else {
            job.state = "FAILED";
            if (job.message == null || job.message.isBlank()) {
                job.message = "Offload 失敗";
            }
        }
        job.result = (result == null || result.isBlank())
                ? "❌ Offload 中止：無法取得詳細錯誤。"
                : result.trim();
        job.finishedAt = System.currentTimeMillis();

        if (onFinished != null) {
            try {
                onFinished.accept(job.result);
            } catch (Exception e) {
                log.warn("Offload completion callback failed for job {}", job.jobId, e);
            }
        }
    }

    private OffloadExecutionSupport.OffloadCommandResult runCommandForOffloadJob(
            java.util.List<String> command,
            long timeoutSeconds,
            String actor) {
        String shellCommand = toShellCommand(command);
        String raw = commandExecutionService.execute(
                shellCommand,
                CommandExecutionService.ExecutionOptions.builder()
                        .trustedRoot()
                        .user(actor)
                        .timeoutSeconds(timeoutSeconds)
                        .build());
        String output = extractToolResult(raw);
        boolean success = !isFailureOutput(output);
        return new OffloadExecutionSupport.OffloadCommandResult(success, output);
    }

    private boolean isFailureOutput(String output) {
        if (output == null) return true;
        String text = output.trim();
        if (text.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        if (text.startsWith(CommandMarkers.SECURITY_VIOLATION)) return true;
        if (lower.contains("command execution failed")) return true;
        if (lower.contains("exception occurred while executing command")) return true;
        if (lower.contains("timed out")) return true;
        return false;
    }

    private String toShellCommand(java.util.List<String> command) {
        if (command == null || command.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String token : command) {
            if (token == null) continue;
            if (sb.length() > 0) sb.append(' ');
            if (isSafeShellToken(token)) {
                sb.append(token);
            } else {
                sb.append(shellQuote(token));
            }
        }
        return sb.toString();
    }

    private boolean isSafeShellToken(String text) {
        return text != null && text.matches("^[a-zA-Z0-9_./:=+\\-]+$");
    }

    private String shellQuote(String text) {
        if (text == null || text.isEmpty()) return "''";
        return "'" + text.replace("'", "'\"'\"'") + "'";
    }

    private void monitorProgress(OffloadJob job, AtomicBoolean running) {
        while (running.get()) {
            refreshCopied(job, false);
            try {
                Thread.sleep(PROGRESS_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        refreshCopied(job, true);
    }

    private void refreshCopied(OffloadJob job, boolean allowCompletePercent) {
        long copied = safeDirectorySize(job.destination);
        if (copied >= 0) {
            job.copiedBytes = copied;
        }

        int percent = calcPercent(job.copiedBytes, job.totalBytes);
        if (!allowCompletePercent && percent >= 100) {
            percent = 99;
        }
        job.percent = percent;

        if ("RUNNING".equals(job.state)) {
            String copiedSize = formatBytes(Math.max(job.copiedBytes, 0));
            String totalSize = job.totalBytes >= 0 ? formatBytes(job.totalBytes) : "未知";
            if (job.percent >= 0) {
                job.message = "複製中 " + job.percent + "% (" + copiedSize + " / " + totalSize + ")";
            } else {
                job.message = "複製中 (" + copiedSize + " / " + totalSize + ")";
            }
        }
    }

    private int calcPercent(long copiedBytes, long totalBytes) {
        if (copiedBytes < 0 || totalBytes <= 0) return -1;
        long raw = copiedBytes * 100L / totalBytes;
        if (raw < 0) return 0;
        if (raw > 100) return 100;
        return (int) raw;
    }

    private long safeDirectorySize(Path directory) {
        if (directory == null) return -1L;
        if (!Files.exists(directory)) return 0L;
        try (var walk = Files.walk(directory)) {
            return walk
                    .filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException ignored) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (Exception e) {
            return -1L;
        }
    }

    private OffloadProgressSnapshot toSnapshot(OffloadJob job) {
        boolean done = "SUCCEEDED".equals(job.state) || "FAILED".equals(job.state);
        long copied = Math.max(job.copiedBytes, 0L);
        String copiedSize = formatBytes(copied);
        String totalSize = job.totalBytes >= 0 ? formatBytes(job.totalBytes) : "未知";
        return new OffloadProgressSnapshot(
                job.jobId,
                job.state,
                job.percent,
                copied,
                job.totalBytes,
                copiedSize,
                totalSize,
                job.message,
                job.result,
                done,
                job.startedAt,
                job.finishedAt
        );
    }

    private void clearExpiredJobs() {
        long now = System.currentTimeMillis();
        jobs.entrySet().removeIf(e -> {
            Long finishedAt = e.getValue().finishedAt;
            return finishedAt != null && now - finishedAt > FINISHED_JOB_RETENTION_MS;
        });
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "未知";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIdx = 0;
        while (value >= 1024.0 && unitIdx < units.length - 1) {
            value /= 1024.0;
            unitIdx++;
        }
        if (unitIdx == 0) return bytes + " B";
        return String.format(Locale.ROOT, "%.2f %s", value, units[unitIdx]);
    }

    public record OffloadProgressSnapshot(
            String jobId,
            String state,
            int percent,
            long copiedBytes,
            long totalBytes,
            String copiedSize,
            String totalSize,
            String message,
            String result,
            boolean done,
            long startedAt,
            Long finishedAt
    ) {}

    private static final class OffloadJob {
        private final String jobId;
        private final String username;
        private final Path source;
        private final Path targetRoot;
        private final Path destination;
        private final String sourceLockKey;

        private volatile String state = "QUEUED";
        private volatile int percent = -1;
        private volatile long copiedBytes = 0L;
        private volatile long totalBytes = -1L;
        private volatile String message = "排隊中...";
        private volatile String result = null;
        private volatile long startedAt = 0L;
        private volatile Long finishedAt = null;

        private OffloadJob(
                String jobId,
                String username,
                Path source,
                Path targetRoot,
                Path destination,
                String sourceLockKey) {
            this.jobId = jobId;
            this.username = username;
            this.source = source;
            this.targetRoot = targetRoot;
            this.destination = destination;
            this.sourceLockKey = sourceLockKey;
        }
    }
}
