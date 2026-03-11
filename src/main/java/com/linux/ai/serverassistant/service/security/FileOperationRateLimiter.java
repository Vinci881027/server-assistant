package com.linux.ai.serverassistant.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Per-user rate limiter for file read operations.
 *
 * Applies to tool-driven read-heavy actions (e.g. listDirectory/readFileContent)
 * to prevent repeated polling from exhausting server resources.
 *
 * Uses a sliding window per username by storing recent request timestamps.
 */
@Service
public class FileOperationRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FileOperationRateLimiter.class);

    private final int maxRequestsPerWindow;
    private final long windowMs;
    private final int maxTrackedUsers;
    private final Clock clock;

    private final Map<String, WindowState> userWindows = new HashMap<>();
    private final Object lock = new Object();

    @Autowired
    public FileOperationRateLimiter(
            @Value("${app.security.file.max-requests-per-window:20}") int maxRequestsPerWindow,
            @Value("${app.security.file.window-minutes:1}") long windowMinutes,
            @Value("${app.security.file.max-tracked-users:500}") int maxTrackedUsers) {
        this(maxRequestsPerWindow, Duration.ofMinutes(windowMinutes), maxTrackedUsers, Clock.systemUTC());
    }

    FileOperationRateLimiter(int maxRequestsPerWindow, Duration window, int maxTrackedUsers, Clock clock) {
        if (maxRequestsPerWindow <= 0) throw new IllegalArgumentException("maxRequestsPerWindow must be > 0");
        if (window == null || window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be > 0");
        if (maxTrackedUsers <= 0) throw new IllegalArgumentException("maxTrackedUsers must be > 0");
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMs = window.toMillis();
        this.maxTrackedUsers = maxTrackedUsers;
        this.clock = clock;
    }

    /**
     * Records a request for the given user and returns the rate-limit decision.
     *
     * @param username authenticated username
     * @return 0 if request is allowed; positive retry seconds if rejected
     */
    public long tryConsume(String username) {
        if (username == null || username.isBlank()) return 0;

        long now = clock.millis();
        synchronized (lock) {
            WindowState state = userWindows.get(username);
            if (state == null) {
                ensureCapacity(now);
                state = new WindowState();
                userWindows.put(username, state);
            }

            pruneExpired(state, now);

            if (state.requestTimestampsMs.size() >= maxRequestsPerWindow) {
                Long oldest = state.requestTimestampsMs.peekFirst();
                long retryAfterSeconds = 1;
                if (oldest != null) {
                    long retryAfterMs = windowMs - (now - oldest);
                    retryAfterSeconds = Math.max(1, (retryAfterMs + 999) / 1000);
                }
                log.warn(
                        "File operation rate limit exceeded for user '{}': activeRequests={}, maxRequests={}, windowMs={}, retryAfter={}s",
                        username,
                        state.requestTimestampsMs.size(),
                        maxRequestsPerWindow,
                        windowMs,
                        retryAfterSeconds);
                return retryAfterSeconds;
            }

            state.requestTimestampsMs.addLast(now);
            return 0;
        }
    }

    @Scheduled(fixedRateString = "${app.security.file.cleanup-interval-ms:60000}")
    public void cleanupExpiredEntries() {
        long now = clock.millis();
        synchronized (lock) {
            userWindows.entrySet().removeIf(e -> {
                WindowState state = e.getValue();
                if (state == null) return true;
                pruneExpired(state, now);
                return state.requestTimestampsMs.isEmpty();
            });
        }
    }

    private void ensureCapacity(long now) {
        if (userWindows.size() < maxTrackedUsers) return;
        userWindows.entrySet().removeIf(e -> {
            WindowState state = e.getValue();
            if (state == null) return true;
            pruneExpired(state, now);
            return state.requestTimestampsMs.isEmpty();
        });
        if (userWindows.size() < maxTrackedUsers) return;
        userWindows.entrySet().stream()
                .min(Map.Entry.comparingByValue((a, b) -> Long.compare(a.oldestRequestTimestamp(), b.oldestRequestTimestamp())))
                .map(Map.Entry::getKey)
                .ifPresent(userWindows::remove);
    }

    private void pruneExpired(WindowState state, long now) {
        long cutoff = now - windowMs;
        while (!state.requestTimestampsMs.isEmpty() && state.requestTimestampsMs.peekFirst() <= cutoff) {
            state.requestTimestampsMs.removeFirst();
        }
    }

    private static final class WindowState {
        private final Deque<Long> requestTimestampsMs = new ArrayDeque<>();

        long oldestRequestTimestamp() {
            Long oldest = requestTimestampsMs.peekFirst();
            return oldest == null ? Long.MAX_VALUE : oldest;
        }
    }
}
