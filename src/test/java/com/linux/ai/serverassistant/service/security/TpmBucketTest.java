package com.linux.ai.serverassistant.service.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TpmBucketTest {

    @Test
    void tryConsume_shouldAllowUntilCapacityThenBlock() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        assertEquals(0L, bucket.tryConsume("120b", 60, 60));
        long retryAfterSeconds = bucket.tryConsume("120b", 60, 1);
        assertEquals(1L, retryAfterSeconds);
    }

    @Test
    void tryConsume_shouldRefillOverTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        assertEquals(0L, bucket.tryConsume("120b", 60, 60));
        clock.advance(Duration.ofSeconds(30));
        assertEquals(0L, bucket.tryConsume("120b", 60, 30));
        assertEquals(1L, bucket.tryConsume("120b", 60, 1));
    }

    @Test
    void tryConsume_requestedOverCapacity_shouldCapToCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        assertEquals(0L, bucket.tryConsume("120b", 120, 500));
        long retryAfterSeconds = bucket.tryConsume("120b", 120, 500);
        assertTrue(retryAfterSeconds >= 1L);
        assertTrue(retryAfterSeconds <= 60L);
    }

    @Test
    void tryConsume_invalidInput_shouldBypass() {
        TpmBucket bucket = new TpmBucket(new MutableClock(Instant.parse("2026-01-01T00:00:00Z")));

        assertEquals(0L, bucket.tryConsume(null, 120, 100));
        assertEquals(0L, bucket.tryConsume(" ", 120, 100));
        assertEquals(0L, bucket.tryConsume("120b", 0, 100));
        assertEquals(0L, bucket.tryConsume("120b", 120, 0));
    }

    @Test
    void reconfigure_shouldApplyCapacityBeforeFirstConsume() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        bucket.reconfigure("120b", 180);
        assertEquals(0L, bucket.tryConsume("120b", 60, 180));
        assertEquals(1L, bucket.tryConsume("120b", 60, 1));
    }

    @Test
    void reconfigure_shouldShrinkExistingBucketCapacity() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        assertEquals(0L, bucket.tryConsume("120b", 120, 70));
        bucket.reconfigure("120b", 60);

        assertEquals(0L, bucket.tryConsume("120b", 60, 50));
        assertEquals(1L, bucket.tryConsume("120b", 60, 1));
    }

    @Test
    void peek_shouldNotConsumeTokens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        assertEquals(0L, bucket.peek("120b", 60, 30));
        assertEquals(0L, bucket.consume("120b", 60, 60));
        assertEquals(1L, bucket.consume("120b", 60, 1));
    }

    @Test
    void peek_shouldReturnRetryAfterWithoutChangingBucketState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        assertEquals(0L, bucket.consume("120b", 60, 60));
        assertEquals(1L, bucket.peek("120b", 60, 1));
        assertEquals(1L, bucket.consume("120b", 60, 1));
    }

    @Test
    void consumeWithResult_shouldReturnCappedConsumedTokens() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        TpmBucket.ConsumeResult first = bucket.consumeWithResult("120b", 60, 100);
        assertEquals(0L, first.retryAfterSeconds());
        assertEquals(60L, first.consumedTokens());

        TpmBucket.ConsumeResult second = bucket.consumeWithResult("120b", 60, 1);
        assertEquals(1L, second.retryAfterSeconds());
        assertEquals(0L, second.consumedTokens());
    }

    @Test
    void adjustConsumption_shouldApplyDebitAndRefund() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        assertEquals(0L, bucket.consume("120b", 60, 30));
        assertEquals(20L, bucket.adjustConsumption("120b", 60, 20));
        assertEquals(1L, bucket.consume("120b", 60, 11));
        assertEquals(-10L, bucket.adjustConsumption("120b", 60, -10));
        assertEquals(0L, bucket.consume("120b", 60, 10));
    }

    @Test
    void clear_shouldResetPreviousRateLimitState() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        TpmBucket bucket = new TpmBucket(clock);

        bucket.reconfigure("120b", 180);
        assertEquals(0L, bucket.consume("120b", 60, 180));
        assertEquals(1L, bucket.consume("120b", 60, 1));

        bucket.clear();

        assertEquals(0L, bucket.consume("120b", 60, 60));
        assertEquals(1L, bucket.consume("120b", 60, 1));
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
