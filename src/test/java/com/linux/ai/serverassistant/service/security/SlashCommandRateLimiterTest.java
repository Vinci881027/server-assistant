package com.linux.ai.serverassistant.service.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandRateLimiterTest {

    @Test
    void tryConsume_shouldAllowUpToLimitThenBlock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SlashCommandRateLimiter limiter = new SlashCommandRateLimiter(2, Duration.ofMinutes(1), 100, clock);

        assertEquals(0, limiter.tryConsume("alice"));
        assertEquals(0, limiter.tryConsume("alice"));
        long retryAfterSeconds = limiter.tryConsume("alice");
        assertTrue(retryAfterSeconds > 0);
        assertTrue(retryAfterSeconds <= 60);
    }

    @Test
    void tryConsume_blankUser_shouldBypassLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        SlashCommandRateLimiter limiter = new SlashCommandRateLimiter(1, Duration.ofMinutes(1), 100, clock);

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
    }
}
