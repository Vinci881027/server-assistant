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
 * Per-user rate limiter for deterministic slash/exclamation commands.
 *
 * Uses a sliding window per username by storing recent deterministic-command timestamps.
 */
@Service
public class SlashCommandRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlashCommandRateLimiter.class);

    private final int maxRequestsPerWindow;
    private final long windowMs;
    private final int maxTrackedUsers;
    private final Clock clock;

    private final Map<String, WindowState> userWindows = new HashMap<>();
    private final Object lock = new Object();

    @Autowired
    public SlashCommandRateLimiter(
            @Value("${app.security.slash.max-requests-per-window}") int maxRequestsPerWindow,
            @Value("${app.security.slash.window-minutes}") long windowMinutes,
            @Value("${app.security.slash.max-tracked-users}") int maxTrackedUsers) {
        this(maxRequestsPerWindow, Duration.ofMinutes(windowMinutes), maxTrackedUsers, Clock.systemUTC());
    }

    SlashCommandRateLimiter(int maxRequestsPerWindow, Duration window, int maxTrackedUsers, Clock clock) {
        if (maxRequestsPerWindow <= 0) throw new IllegalArgumentException("maxRequestsPerWindow must be > 0");
        if (window == null || window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be > 0");
        if (maxTrackedUsers <= 0) throw new IllegalArgumentException("maxTrackedUsers must be > 0");
        this.maxRequestsPerWindow = maxRequestsPerWindow;
        this.windowMs = window.toMillis();
        this.maxTrackedUsers = maxTrackedUsers;
        this.clock = clock;
    }

    /**
     * Records a deterministic-command request for the given user.
     *
     * @param username the authenticated username
     * @return 0 if the request is allowed; positive retry seconds if rejected
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
                        "Slash command rate limit exceeded for user '{}': activeRequests={}, maxRequests={}, windowMs={}, retryAfter={}s",
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

    @Scheduled(fixedRateString = "${app.security.slash.cleanup-interval-ms}")
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
