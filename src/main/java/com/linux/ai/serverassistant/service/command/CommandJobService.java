package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.command.CommandExecutionService.ExecutionOptions;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static com.linux.ai.serverassistant.util.ToolResultUtils.extractToolResult;

@Service
public class CommandJobService {

    private static final Logger log = LoggerFactory.getLogger(CommandJobService.class);
    private static final long FINISHED_JOB_RETENTION_MS = 2 * 60 * 60 * 1_000L;

    private final CommandExecutionService commandExecutionService;
    private final UserContext userContext;
    private final Map<String, CommandJob> jobs = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicInteger workerCounter = new AtomicInteger(1);
    private final ExecutorService executor;

    @Autowired
    public CommandJobService(
            CommandExecutionService commandExecutionService,
            UserContext userContext,
            @Value("${app.command.jobs.max-parallel:8}") int maxParallelCommandJobs,
            @Value("${app.command.jobs.queue-capacity:200}") int commandJobQueueCapacity) {
        this.commandExecutionService = commandExecutionService;
        this.userContext = userContext;
        int workers = Math.max(1, maxParallelCommandJobs);
        int queueCapacity = Math.max(1, commandJobQueueCapacity);
        this.executor = new ThreadPoolExecutor(
                workers,
                workers,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "command-job-worker-" + workerCounter.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        log.info("Command job pool initialized: workers={}, queueCapacity={}", workers, queueCapacity);
    }

    public String startCommandJob(String username,
                                  String sessionId,
                                  String command,
                                  Consumer<String> onFinished) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("使用者不可為空");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("命令不可為空");
        }

        clearExpiredJobs();
        String jobId = UUID.randomUUID().toString();
        CommandJob job = new CommandJob(jobId, username.trim(), command.trim());
        jobs.put(jobId, job);
        try {
            executor.submit(() -> runJob(job, sessionId, onFinished));
        } catch (RejectedExecutionException ex) {
            jobs.remove(jobId);
            throw new IllegalStateException("背景任務繁忙中，請稍後再試。");
        }
        return jobId;
    }

    public Optional<CommandJobProgressSnapshot> getJobProgress(String username, String jobId) {
        if (username == null || username.isBlank() || jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }

        clearExpiredJobs();
        CommandJob job = jobs.get(jobId.trim());
        if (job == null) return Optional.empty();
        if (!job.username.equals(username.trim())) return Optional.empty();

        return Optional.of(toSnapshot(job));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    private void runJob(CommandJob job, String sessionId, Consumer<String> onFinished) {
        job.state = "RUNNING";
        job.startedAt = System.currentTimeMillis();
        job.message = "背景任務執行中...";

        String contextKey = "command-job:" + job.jobId;
        String finalMessage;
        try {
            userContext.setCurrentContextKey(contextKey);
            userContext.registerToolSession(contextKey, job.username, sessionId);

            String raw = commandExecutionService.execute(
                    job.command,
                    ExecutionOptions.builder()
                            .confirmed()
                            .skipPendingCheck()
                            .user(job.username)
                            .build());
            String result = extractToolResult(raw, "執行完成。");
            if (isFailure(result)) {
                job.state = "FAILED";
                job.message = "背景任務失敗";
                finalMessage = "❌ 操作失敗：" + result;
            } else {
                job.state = "SUCCEEDED";
                job.message = "背景任務完成";
                finalMessage = "✅ 操作完成：" + result;
            }
        } catch (Exception e) {
            log.warn("Command job {} failed unexpectedly", job.jobId, e);
            job.state = "FAILED";
            job.message = "背景任務失敗";
            finalMessage = "❌ 操作失敗：" + (e.getMessage() == null ? "未知錯誤" : e.getMessage());
        } finally {
            userContext.releaseToolSession(contextKey);
            userContext.clearCurrentContextKey();
        }

        job.result = finalMessage;
        job.finishedAt = System.currentTimeMillis();

        if (onFinished != null) {
            try {
                onFinished.accept(finalMessage);
            } catch (Exception e) {
                log.warn("Command job callback failed for job {}", job.jobId, e);
            }
        }
    }

    private boolean isFailure(String result) {
        if (result == null) return true;
        String trimmed = result.trim();
        if (trimmed.isEmpty()) return false;
        return trimmed.startsWith(CommandMarkers.SECURITY_VIOLATION)
                || trimmed.startsWith("錯誤")
                || trimmed.toLowerCase(java.util.Locale.ROOT).contains("failed");
    }

    private CommandJobProgressSnapshot toSnapshot(CommandJob job) {
        boolean done = "SUCCEEDED".equals(job.state) || "FAILED".equals(job.state);
        return new CommandJobProgressSnapshot(
                job.jobId,
                job.state,
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

    public record CommandJobProgressSnapshot(
            String jobId,
            String state,
            String message,
            String result,
            boolean done,
            long startedAt,
            Long finishedAt
    ) {}

    private static final class CommandJob {
        private final String jobId;
        private final String username;
        private final String command;

        private volatile String state = "QUEUED";
        private volatile String message = "排隊中...";
        private volatile String result = null;
        private volatile long startedAt = 0L;
        private volatile Long finishedAt = null;

        private CommandJob(String jobId, String username, String command) {
            this.jobId = jobId;
            this.username = username;
            this.command = command;
        }
    }
}
