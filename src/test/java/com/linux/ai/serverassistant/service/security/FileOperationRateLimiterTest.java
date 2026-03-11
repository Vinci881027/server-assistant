package com.linux.ai.serverassistant.service.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileOperationRateLimiterTest {

    @Test
    void tryConsume_shouldAllowUpToLimitThenBlock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FileOperationRateLimiter limiter = new FileOperationRateLimiter(2, Duration.ofMinutes(1), 100, clock);

        assertEquals(0, limiter.tryConsume("alice"));
        assertEquals(0, limiter.tryConsume("alice"));
        long retryAfterSeconds = limiter.tryConsume("alice");
        assertTrue(retryAfterSeconds > 0);
        assertTrue(retryAfterSeconds <= 60);
    }

    @Test
    void tryConsume_shouldResetAfterWindowExpires() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FileOperationRateLimiter limiter = new FileOperationRateLimiter(1, Duration.ofMinutes(1), 100, clock);

        assertEquals(0, limiter.tryConsume("alice"));
        assertTrue(limiter.tryConsume("alice") > 0);

        clock.advance(Duration.ofSeconds(61));
        assertEquals(0, limiter.tryConsume("alice"));
    }

    @Test
    void tryConsume_shouldUseSlidingWindowWithoutBoundaryBurst() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FileOperationRateLimiter limiter = new FileOperationRateLimiter(2, Duration.ofSeconds(60), 100, clock);

        assertEquals(0, limiter.tryConsume("alice"));

        clock.advance(Duration.ofSeconds(59));
        assertEquals(0, limiter.tryConsume("alice"));
        assertEquals(1, limiter.tryConsume("alice"));

        // At exactly +60s, the first request expires. The next immediate request is blocked
        // until the second request at +59s leaves the sliding window.
        clock.advance(Duration.ofSeconds(1));
        assertEquals(0, limiter.tryConsume("alice"));
        assertEquals(59, limiter.tryConsume("alice"));
    }

    @Test
    void tryConsume_blankUser_shouldBypassLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        FileOperationRateLimiter limiter = new FileOperationRateLimiter(1, Duration.ofMinutes(1), 100, clock);

        assertEquals(0, limiter.tryConsume(null));
        assertEquals(0, limiter.tryConsume(""));
        assertEquals(0, limiter.tryConsume("   "));
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
