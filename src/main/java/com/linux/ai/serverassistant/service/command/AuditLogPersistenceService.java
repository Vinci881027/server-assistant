package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Best-effort audit log persistence with bounded queue + retry.
 *
 * Primary path is still synchronous save(). On failure, entries are queued and retried
 * in the background with exponential backoff to reduce silent audit-log loss.
 */
public final class AuditLogPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogPersistenceService.class);

    private static final int MAX_RETRY_ATTEMPTS = 5;
    private static final int MAX_QUEUE_SIZE = 5000;
    private static final long INITIAL_RETRY_DELAY_MS = 200L;
    private static final long MAX_RETRY_DELAY_MS = 5000L;

    private static final DelayQueue<RetryEntry> RETRY_QUEUE = new DelayQueue<>();
    private static final AtomicInteger QUEUED_COUNT = new AtomicInteger(0);
    private static final AtomicBoolean WORKER_STARTED = new AtomicBoolean(false);

    private AuditLogPersistenceService() {}

    public static void persist(CommandLogRepository repository, CommandLog commandLog, String source) {
        Objects.requireNonNull(repository, "repository");
        if (commandLog == null) return;
        ensureWorkerStarted();

        try {
            repository.save(commandLog);
        } catch (Exception ex) {
            RetryEntry entry = RetryEntry.firstAttempt(repository, copyOf(commandLog), source);
            if (!enqueue(entry)) {
                log.error("[Audit] Retry queue full; dropping audit log (source={})", safeSource(source));
                return;
            }
            log.warn("[Audit] Immediate save failed; queued retry (source={}, reason={})",
                    safeSource(source), ex.getMessage());
        }
    }

    private static void ensureWorkerStarted() {
        if (!WORKER_STARTED.compareAndSet(false, true)) return;
        Thread worker = new Thread(AuditLogPersistenceService::runRetryLoop, "audit-log-retry-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static void runRetryLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            RetryEntry entry = null;
            try {
                entry = RETRY_QUEUE.take();
                QUEUED_COUNT.decrementAndGet();
                entry.repository.save(entry.commandLog);
                log.info("[Audit] Retry succeeded (source={}, attempt={})",
                        safeSource(entry.source), entry.attempt);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception ex) {
                if (entry == null) {
                    log.error("[Audit] Unexpected retry worker failure: {}", ex.getMessage(), ex);
                    continue;
                }
                if (entry.attempt >= MAX_RETRY_ATTEMPTS) {
                    log.error("[Audit] Dropping audit log after max retries (source={}, attempts={}, reason={})",
                            safeSource(entry.source), entry.attempt, ex.getMessage());
                    continue;
                }

                RetryEntry next = entry.nextAttempt();
                if (!enqueue(next)) {
                    log.error("[Audit] Retry queue full; dropping retried audit log (source={}, attempt={})",
                            safeSource(entry.source), next.attempt);
                    continue;
                }
                log.warn("[Audit] Retry failed; re-queued (source={}, nextAttempt={}, reason={})",
                        safeSource(entry.source), next.attempt, ex.getMessage());
            }
        }
    }

    private static boolean enqueue(RetryEntry entry) {
        int sizeAfterIncrement = QUEUED_COUNT.incrementAndGet();
        if (sizeAfterIncrement > MAX_QUEUE_SIZE) {
            QUEUED_COUNT.decrementAndGet();
            return false;
        }
        RETRY_QUEUE.offer(entry);
        return true;
    }

    private static long retryDelayMs(int attempt) {
        long exponent = Math.max(0, attempt - 1);
        long delay = INITIAL_RETRY_DELAY_MS << Math.min(20, exponent);
        return Math.min(delay, MAX_RETRY_DELAY_MS);
    }

    private static String safeSource(String source) {
        return (source == null || source.isBlank()) ? "unknown" : source;
    }

    private static CommandLog copyOf(CommandLog original) {
        CommandLog copy = new CommandLog();
        copy.setUsername(original.getUsername());
        copy.setCommand(original.getCommand());
        copy.setExecutionTime(original.getExecutionTime());
        copy.setOutput(original.getOutput());
        copy.setSuccess(original.isSuccess());
        copy.setExitCode(original.getExitCode());
        copy.setCommandType(original.getCommandType());
        return copy;
    }

    private static final class RetryEntry implements Delayed {
        private final CommandLogRepository repository;
        private final CommandLog commandLog;
        private final String source;
        private final int attempt;
        private final long dueAtNanos;

        private RetryEntry(CommandLogRepository repository,
                           CommandLog commandLog,
                           String source,
                           int attempt,
                           long dueAtNanos) {
            this.repository = repository;
            this.commandLog = commandLog;
            this.source = source;
            this.attempt = attempt;
            this.dueAtNanos = dueAtNanos;
        }

        private static RetryEntry firstAttempt(CommandLogRepository repository, CommandLog commandLog, String source) {
            return new RetryEntry(repository, commandLog, source, 1,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryDelayMs(1)));
        }

        private RetryEntry nextAttempt() {
            int nextAttempt = attempt + 1;
            return new RetryEntry(repository, commandLog, source, nextAttempt,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(retryDelayMs(nextAttempt)));
        }

        @Override
        public long getDelay(TimeUnit unit) {
            long remaining = dueAtNanos - System.nanoTime();
            return unit.convert(remaining, TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) return 0;
            long diff = getDelay(TimeUnit.NANOSECONDS) - other.getDelay(TimeUnit.NANOSECONDS);
            return Long.compare(diff, 0L);
        }
    }
}
