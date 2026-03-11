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
import java.util.Iterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-user token-per-minute limiter for chat requests.
 *
 * Uses a sliding window by tracking token usage events per user.
 */
@Service
public class UserTpmLimiter {

    private static final Logger log = LoggerFactory.getLogger(UserTpmLimiter.class);

    private final int concurrentUsersCapacityAssumption;
    private final long windowMs;
    private final int maxTrackedUsers;
    private final Clock clock;

    private final Map<String, WindowState> userWindows = new HashMap<>();
    private final Map<String, UserExpiry> scheduledExpiryByUser = new HashMap<>();
    private final PriorityQueue<UserExpiry> expiryQueue = new PriorityQueue<>((a, b) -> Long.compare(a.expiresAtMs, b.expiresAtMs));
    private final AtomicInteger activeUserCount = new AtomicInteger(0);
    private final Object lock = new Object();
    private long nextEventId = 1L;

    @Autowired
    public UserTpmLimiter(
            @Value("${app.security.chat.user-tpm-concurrent-users-capacity-assumption:"
                    + "${app.security.chat.user-tpm-expected-max-concurrent-users}}")
            int concurrentUsersCapacityAssumption,
            @Value("${app.security.chat.user-tpm-window-minutes}") long windowMinutes,
            @Value("${app.security.chat.user-tpm-max-tracked-users}") int maxTrackedUsers) {
        this(concurrentUsersCapacityAssumption, Duration.ofMinutes(windowMinutes), maxTrackedUsers, Clock.systemUTC());
    }

    UserTpmLimiter(int concurrentUsersCapacityAssumption, Duration window, int maxTrackedUsers, Clock clock) {
        if (concurrentUsersCapacityAssumption <= 0) {
            throw new IllegalArgumentException("concurrentUsersCapacityAssumption must be > 0");
        }
        if (window == null || window.isZero() || window.isNegative()) throw new IllegalArgumentException("window must be > 0");
        if (maxTrackedUsers <= 0) throw new IllegalArgumentException("maxTrackedUsers must be > 0");
        this.concurrentUsersCapacityAssumption = concurrentUsersCapacityAssumption;
        this.windowMs = window.toMillis();
        this.maxTrackedUsers = maxTrackedUsers;
        this.clock = clock;
    }

    /**
     * Records token usage for the given user and returns the rate-limit decision.
     *
     * @param username authenticated username
     * @param requestedTokens estimated token usage for this request
     * @param totalTokensPerWindow total TPM budget for this model in current window
     * @return 0 if allowed; positive retry seconds if rejected
     */
    public long tryConsume(String username, long requestedTokens, long totalTokensPerWindow) {
        return tryConsumeWithHandle(username, requestedTokens, totalTokensPerWindow).retryAfterSeconds();
    }

    /**
     * Records token usage and returns a consume handle that can be rolled back if needed.
     *
     * @param username authenticated username
     * @param requestedTokens estimated token usage for this request
     * @param totalTokensPerWindow total TPM budget for this model in current window
     * @return consume result with retry-after and optional event handle
     */
    public ConsumeResult tryConsumeWithHandle(String username, long requestedTokens, long totalTokensPerWindow) {
        if (username == null || username.isBlank()) return new ConsumeResult(0L, 0L, 0L);
        if (requestedTokens <= 0L) return new ConsumeResult(0L, 0L, 0L);
        if (totalTokensPerWindow <= 0L) return new ConsumeResult(0L, 0L, 0L);

        long now = clock.millis();
        synchronized (lock) {
            evictExpiredUsersByDeadline(now);
            WindowState state = userWindows.get(username);
            if (state == null) {
                ensureCapacity(now);
                state = new WindowState();
                userWindows.put(username, state);
            }

            boolean userIsAlreadyActive = pruneExpiredAndTrackActivity(username, state, now);
            int activeUsers = activeUserCount.get();
            if (!userIsAlreadyActive) {
                // Include current user for share-cap calculation if this request is accepted.
                activeUsers++;
            }

            long maxTokensPerWindow = resolveUserWindowCap(totalTokensPerWindow, activeUsers);
            long cappedRequested = Math.max(1L, Math.min(requestedTokens, maxTokensPerWindow));

            long projectedUsage = state.tokensInWindow + cappedRequested;
            if (projectedUsage > maxTokensPerWindow) {
                long tokensToFree = projectedUsage - maxTokensPerWindow;
                long freedTokens = 0L;
                long retryAtMs = now + windowMs;
                for (TokenEvent event : state.tokenEvents) {
                    freedTokens += event.tokens;
                    retryAtMs = event.timestampMs + windowMs;
                    if (freedTokens >= tokensToFree) {
                        break;
                    }
                }
                long retryAfterMs = Math.max(1L, retryAtMs - now);
                long retryAfterSeconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
                log.warn(
                        "Per-user TPM limit exceeded for user '{}': activeTokens={}, requestedTokens={}, capPerWindow={}, activeUsers={}, concurrentUsersCapacityAssumption={}, totalTpm={}, retryAfter={}s",
                        username,
                        state.tokensInWindow,
                        cappedRequested,
                        maxTokensPerWindow,
                        activeUsers,
                        concurrentUsersCapacityAssumption,
                        totalTokensPerWindow,
                        retryAfterSeconds);
                return new ConsumeResult(retryAfterSeconds, 0L, 0L);
            }

            long eventId = nextEventId++;
            state.tokenEvents.addLast(new TokenEvent(eventId, now, cappedRequested));
            state.tokensInWindow = safeAdd(state.tokensInWindow, cappedRequested);
            if (!userIsAlreadyActive) {
                activeUserCount.incrementAndGet();
                scheduleNextUserExpiry(username, state);
            }
            return new ConsumeResult(0L, eventId, cappedRequested);
        }
    }

    /**
     * Rolls back a previously recorded token event.
     *
     * @param username authenticated username
     * @param eventId event handle returned by {@link #tryConsumeWithHandle(String, long, long)}
     * @return true when rollback succeeded; false when event is missing/expired
     */
    public boolean rollback(String username, long eventId) {
        if (username == null || username.isBlank() || eventId <= 0L) {
            return false;
        }
        synchronized (lock) {
            long now = clock.millis();
            evictExpiredUsersByDeadline(now);
            WindowState state = userWindows.get(username);
            if (state == null) {
                return false;
            }
            boolean userIsActive = pruneExpiredAndTrackActivity(username, state, now);
            if (!userIsActive) {
                removeUserWindow(username);
                return false;
            }
            long oldestBeforeRollback = state.oldestActiveTimestamp();
            for (Iterator<TokenEvent> iterator = state.tokenEvents.iterator(); iterator.hasNext(); ) {
                TokenEvent event = iterator.next();
                if (event.id == eventId) {
                    iterator.remove();
                    state.tokensInWindow -= event.tokens;
                    if (state.tokensInWindow < 0L) {
                        state.tokensInWindow = 0L;
                    }
                    if (state.tokenEvents.isEmpty()) {
                        decrementActiveUserCount();
                        removeUserWindow(username);
                    } else if (state.oldestActiveTimestamp() != oldestBeforeRollback) {
                        scheduleNextUserExpiry(username, state);
                    }
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Applies a post-request usage delta to an existing event.
     * Positive delta means additional debit; negative delta means refund.
     *
     * @param username authenticated username
     * @param eventId event handle returned by {@link #tryConsumeWithHandle(String, long, long)}
     * @param deltaTokens signed token delta
     * @return applied delta, 0 when event is missing/expired
     */
    public long adjustEventTokens(String username, long eventId, long deltaTokens) {
        if (username == null || username.isBlank() || eventId <= 0L || deltaTokens == 0L) {
            return 0L;
        }
        synchronized (lock) {
            long now = clock.millis();
            evictExpiredUsersByDeadline(now);
            WindowState state = userWindows.get(username);
            if (state == null) {
                return 0L;
            }
            boolean userIsActive = pruneExpiredAndTrackActivity(username, state, now);
            if (!userIsActive) {
                removeUserWindow(username);
                return 0L;
            }
            long oldestBeforeAdjust = state.oldestActiveTimestamp();
            for (Iterator<TokenEvent> iterator = state.tokenEvents.iterator(); iterator.hasNext(); ) {
                TokenEvent event = iterator.next();
                if (event.id != eventId) {
                    continue;
                }

                if (deltaTokens > 0L) {
                    long beforeTokens = event.tokens;
                    event.tokens = safeAdd(event.tokens, deltaTokens);
                    long applied = event.tokens - beforeTokens;
                    state.tokensInWindow = safeAdd(state.tokensInWindow, applied);
                    return applied;
                }

                long requestedRefund = Math.abs(deltaTokens);
                long appliedRefund = Math.min(event.tokens, requestedRefund);
                if (appliedRefund <= 0L) {
                    return 0L;
                }
                event.tokens -= appliedRefund;
                state.tokensInWindow -= appliedRefund;
                if (state.tokensInWindow < 0L) {
                    state.tokensInWindow = 0L;
                }
                if (event.tokens == 0L) {
                    iterator.remove();
                }

                if (state.tokenEvents.isEmpty()) {
                    decrementActiveUserCount();
                    removeUserWindow(username);
                } else if (state.oldestActiveTimestamp() != oldestBeforeAdjust) {
                    scheduleNextUserExpiry(username, state);
                }
                return -appliedRefund;
            }
            return 0L;
        }
    }

    @Scheduled(fixedRateString = "${app.security.chat.user-tpm-cleanup-interval-ms}")
    public void cleanupExpiredEntries() {
        long now = clock.millis();
        synchronized (lock) {
            evictExpiredUsersByDeadline(now);
            userWindows.entrySet().removeIf(entry -> {
                WindowState state = entry.getValue();
                if (state == null) {
                    removeScheduledExpiry(entry.getKey());
                    return true;
                }
                return !pruneExpiredAndTrackActivity(entry.getKey(), state, now);
            });
        }
    }

    private void ensureCapacity(long now) {
        evictExpiredUsersByDeadline(now);
        if (userWindows.size() < maxTrackedUsers) return;
        userWindows.entrySet().removeIf(entry -> {
            WindowState state = entry.getValue();
            if (state == null) {
                removeScheduledExpiry(entry.getKey());
                return true;
            }
            boolean active = pruneExpiredAndTrackActivity(entry.getKey(), state, now);
            if (!active) {
                removeScheduledExpiry(entry.getKey());
            }
            return !active;
        });
        if (userWindows.size() < maxTrackedUsers) return;
        userWindows.entrySet().stream()
                .min(Map.Entry.comparingByValue((a, b) -> Long.compare(a.oldestActiveTimestamp(), b.oldestActiveTimestamp())))
                .map(Map.Entry::getKey)
                .ifPresent(evictedUser -> {
                    WindowState evictedState = userWindows.remove(evictedUser);
                    removeScheduledExpiry(evictedUser);
                    if (evictedState != null && evictedState.isActive()) {
                        decrementActiveUserCount();
                    }
                });
    }

    private void pruneExpired(WindowState state, long now) {
        long cutoff = now - windowMs;
        while (!state.tokenEvents.isEmpty() && state.tokenEvents.peekFirst().timestampMs <= cutoff) {
            TokenEvent expired = state.tokenEvents.removeFirst();
            state.tokensInWindow -= expired.tokens;
        }
        if (state.tokensInWindow < 0L) {
            state.tokensInWindow = 0L;
        }
    }

    private long resolveUserWindowCap(long totalTokensPerWindow, int activeUsers) {
        long safeTotalTokens = Math.max(1L, totalTokensPerWindow);
        int normalizedActiveUsers = Math.max(1, activeUsers);
        int effectiveDivisor = Math.min(concurrentUsersCapacityAssumption, normalizedActiveUsers);
        return Math.max(1L, safeTotalTokens / effectiveDivisor);
    }

    private boolean pruneExpiredAndTrackActivity(String username, WindowState state, long now) {
        boolean wasActive = state.isActive();
        long oldestBefore = wasActive ? state.oldestActiveTimestamp() : Long.MAX_VALUE;
        pruneExpired(state, now);
        boolean isActive = state.isActive();
        if (wasActive && !isActive) {
            decrementActiveUserCount();
            removeScheduledExpiry(username);
        } else if (isActive && (!wasActive || state.oldestActiveTimestamp() != oldestBefore)) {
            scheduleNextUserExpiry(username, state);
        }
        return isActive;
    }

    private void evictExpiredUsersByDeadline(long now) {
        while (!expiryQueue.isEmpty()) {
            UserExpiry expiry = expiryQueue.peek();
            if (expiry.expiresAtMs > now) {
                return;
            }
            expiryQueue.poll();
            UserExpiry scheduledExpiry = scheduledExpiryByUser.get(expiry.username);
            if (scheduledExpiry != expiry) {
                continue;
            }
            scheduledExpiryByUser.remove(expiry.username);
            WindowState state = userWindows.get(expiry.username);
            if (state == null || !state.isActive()) {
                continue;
            }
            if (state.oldestActiveTimestamp() != expiry.expectedOldestTimestampMs) {
                scheduleNextUserExpiry(expiry.username, state);
                continue;
            }
            boolean isActive = pruneExpiredAndTrackActivity(expiry.username, state, now);
            if (!isActive) {
                removeUserWindow(expiry.username);
            }
        }
    }

    private void scheduleNextUserExpiry(String username, WindowState state) {
        if (!state.isActive()) {
            removeScheduledExpiry(username);
            return;
        }
        long oldestTimestamp = state.oldestActiveTimestamp();
        UserExpiry current = scheduledExpiryByUser.get(username);
        if (current != null && current.expectedOldestTimestampMs == oldestTimestamp) {
            return;
        }
        if (current != null) {
            expiryQueue.remove(current);
        }
        UserExpiry next = new UserExpiry(username, oldestTimestamp, oldestTimestamp + windowMs);
        scheduledExpiryByUser.put(username, next);
        expiryQueue.add(next);
    }

    private void removeUserWindow(String username) {
        userWindows.remove(username);
        removeScheduledExpiry(username);
    }

    private void removeScheduledExpiry(String username) {
        UserExpiry scheduledExpiry = scheduledExpiryByUser.remove(username);
        if (scheduledExpiry != null) {
            expiryQueue.remove(scheduledExpiry);
        }
    }

    private void decrementActiveUserCount() {
        activeUserCount.updateAndGet(current -> Math.max(0, current - 1));
    }

    private long safeAdd(long current, long delta) {
        if (delta > 0L && current > Long.MAX_VALUE - delta) {
            return Long.MAX_VALUE;
        }
        if (delta < 0L && current < Long.MIN_VALUE - delta) {
            return Long.MIN_VALUE;
        }
        return current + delta;
    }

    private static final class WindowState {
        private final Deque<TokenEvent> tokenEvents = new ArrayDeque<>();
        private long tokensInWindow;

        boolean isActive() {
            return !tokenEvents.isEmpty();
        }

        long oldestActiveTimestamp() {
            TokenEvent oldest = tokenEvents.peekFirst();
            return oldest == null ? Long.MAX_VALUE : oldest.timestampMs;
        }
    }

    public record ConsumeResult(long retryAfterSeconds, long eventId, long consumedTokens) {
        public ConsumeResult(long retryAfterSeconds, long eventId) {
            this(retryAfterSeconds, eventId, 0L);
        }
    }

    private record UserExpiry(String username, long expectedOldestTimestampMs, long expiresAtMs) {
    }

    private static final class TokenEvent {
        private final long id;
        private final long timestampMs;
        private long tokens;

        private TokenEvent(long id, long timestampMs, long tokens) {
            this.id = id;
            this.timestampMs = timestampMs;
            this.tokens = tokens;
        }
    }
}
