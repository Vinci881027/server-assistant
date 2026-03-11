package com.linux.ai.serverassistant.service.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserTpmLimiterTest {

    private static final long TOTAL_TPM = 8_000L;
    private static final int CONCURRENT_USERS_CAPACITY_ASSUMPTION = 2;

    @Test
    void tryConsume_singleActiveUser_shouldAllowFullModelBudgetThenBlock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        assertEquals(0L, limiter.tryConsume("alice", 4_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("alice", 4_000, TOTAL_TPM));
        long retryAfter = limiter.tryConsume("alice", 1, TOTAL_TPM);

        assertTrue(retryAfter > 0L);
        assertTrue(retryAfter <= 60L);
    }

    @Test
    void tryConsume_shouldUseSlidingWindowForTokenBudget_whenMultipleUsersActive() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofSeconds(60), 100, clock);

        assertEquals(0L, limiter.tryConsume("alice", 2_500, TOTAL_TPM));
        clock.advance(Duration.ofSeconds(59));
        assertEquals(0L, limiter.tryConsume("bob", 100, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("alice", 1_500, TOTAL_TPM));
        assertEquals(1L, limiter.tryConsume("alice", 1, TOTAL_TPM));

        clock.advance(Duration.ofSeconds(1));
        assertEquals(0L, limiter.tryConsume("alice", 1, TOTAL_TPM));
        assertEquals(59L, limiter.tryConsume("alice", 2_500, TOTAL_TPM));
    }

    @Test
    void tryConsume_requestedOverCapacity_shouldCapToWindowLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        assertEquals(0L, limiter.tryConsume("alice", 8_000, TOTAL_TPM));
        long retryAfter = limiter.tryConsume("alice", 1, TOTAL_TPM);

        assertTrue(retryAfter > 0L);
    }

    @Test
    void tryConsume_whenCapacityReached_shouldEvictOldestUserWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofSeconds(60), 1, clock);

        assertEquals(0L, limiter.tryConsume("alice", 1_000, TOTAL_TPM));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0L, limiter.tryConsume("bob", 1_000, TOTAL_TPM));
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0L, limiter.tryConsume("alice", 4_000, TOTAL_TPM));
    }

    @Test
    void tryConsume_blankUserOrInvalidTokens_shouldBypassLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        assertEquals(0L, limiter.tryConsume(null, 1_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("", 1_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("   ", 1_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("alice", 0, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("alice", -1, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("alice", 1_000, 0));
    }

    @Test
    void tryConsume_whenUserCountExceedsAssumption_shouldUseAssumptionAsUpperBound() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(2, Duration.ofMinutes(1), 100, clock);

        assertEquals(0L, limiter.tryConsume("alice", 4_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("bob", 1, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("carol", 1, TOTAL_TPM));
        long retryAfter = limiter.tryConsume("alice", 1, TOTAL_TPM);

        assertTrue(retryAfter > 0L);
        assertTrue(retryAfter <= 60L);
    }

    @Test
    void rollback_shouldReleaseActiveUserShareImmediately() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        assertEquals(0L, limiter.tryConsumeWithHandle("alice", 4_000, TOTAL_TPM).retryAfterSeconds());
        UserTpmLimiter.ConsumeResult bobConsume = limiter.tryConsumeWithHandle("bob", 100, TOTAL_TPM);
        assertEquals(0L, bobConsume.retryAfterSeconds());

        assertTrue(limiter.rollback("bob", bobConsume.eventId()));
        assertEquals(0L, limiter.tryConsume("alice", 4_000, TOTAL_TPM));
    }

    @Test
    void cleanupExpiredEntries_shouldUpdateActiveUsersForSubsequentBudgeting() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofSeconds(60), 100, clock);

        assertEquals(0L, limiter.tryConsume("alice", 1_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("bob", 1_000, TOTAL_TPM));

        clock.advance(Duration.ofSeconds(61));
        limiter.cleanupExpiredEntries();

        assertEquals(0L, limiter.tryConsume("alice", 6_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("alice", 2_000, TOTAL_TPM));
    }

    @Test
    void tryConsume_shouldDropExpiredActiveUsersWithoutFullScan() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofSeconds(60), 100, clock);

        assertEquals(0L, limiter.tryConsume("alice", 1_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("bob", 1_000, TOTAL_TPM));

        clock.advance(Duration.ofSeconds(61));

        assertEquals(0L, limiter.tryConsume("alice", 6_000, TOTAL_TPM));
        assertEquals(0L, limiter.tryConsume("alice", 2_000, TOTAL_TPM));
    }

    @Test
    void tryConsumeWithHandle_whenRolledBack_shouldRestoreWindowBudget() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        UserTpmLimiter.ConsumeResult result = limiter.tryConsumeWithHandle("alice", 4_000, TOTAL_TPM);
        assertEquals(0L, result.retryAfterSeconds());
        assertTrue(result.eventId() > 0L);
        assertTrue(limiter.rollback("alice", result.eventId()));
        assertEquals(0L, limiter.tryConsume("alice", 4_000, TOTAL_TPM));
    }

    @Test
    void rollback_whenEventMissing_shouldReturnFalse() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        assertFalse(limiter.rollback("alice", 999L));
        assertFalse(limiter.rollback(" ", 1L));
        assertFalse(limiter.rollback("alice", 0L));
    }

    @Test
    void adjustEventTokens_shouldApplyPositiveAndNegativeDelta() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        UserTpmLimiter.ConsumeResult result = limiter.tryConsumeWithHandle("alice", 1_000, TOTAL_TPM);
        assertEquals(0L, result.retryAfterSeconds());
        assertEquals(1_000L, result.consumedTokens());

        assertEquals(200L, limiter.adjustEventTokens("alice", result.eventId(), 200L));
        assertEquals(0L, limiter.tryConsume("alice", 6_800, TOTAL_TPM));
        assertTrue(limiter.tryConsume("alice", 1, TOTAL_TPM) > 0L);

        assertEquals(-1_200L, limiter.adjustEventTokens("alice", result.eventId(), -5_000L));
        assertEquals(0L, limiter.tryConsume("alice", 1_200, TOTAL_TPM));
    }

    @Test
    void adjustEventTokens_whenEventMissing_shouldReturnZero() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        assertEquals(0L, limiter.adjustEventTokens("alice", 999L, 100L));
        assertEquals(0L, limiter.adjustEventTokens("alice", 0L, 100L));
        assertEquals(0L, limiter.adjustEventTokens(" ", 1L, 100L));
    }

    @Test
    void scheduleNextUserExpiry_shouldKeepSingleExpiryPerUser() throws Exception {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        UserTpmLimiter limiter = new UserTpmLimiter(CONCURRENT_USERS_CAPACITY_ASSUMPTION, Duration.ofMinutes(1), 100, clock);

        UserTpmLimiter.ConsumeResult oldest = limiter.tryConsumeWithHandle("alice", 1, TOTAL_TPM);
        assertEquals(0L, oldest.retryAfterSeconds());

        for (int i = 0; i < 50; i++) {
            clock.advance(Duration.ofMillis(100));
            UserTpmLimiter.ConsumeResult next = limiter.tryConsumeWithHandle("alice", 1, TOTAL_TPM);
            assertEquals(0L, next.retryAfterSeconds());
            assertTrue(limiter.rollback("alice", oldest.eventId()));
            oldest = next;
        }

        assertEquals(1, readPriorityQueueField(limiter, "expiryQueue").size());
        assertEquals(1, readMapField(limiter, "scheduledExpiryByUser").size());
    }

    @SuppressWarnings("unchecked")
    private static PriorityQueue<Object> readPriorityQueueField(UserTpmLimiter limiter, String fieldName) throws Exception {
        Field field = UserTpmLimiter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (PriorityQueue<Object>) field.get(limiter);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMapField(UserTpmLimiter limiter, String fieldName) throws Exception {
        Field field = UserTpmLimiter.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, Object>) field.get(limiter);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant initial) {
            this(initial, ZoneId.of("UTC"));
        }

        private MutableClock(Instant initial, ZoneId zone) {
            this.instant = initial;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
