package com.linux.ai.serverassistant.service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginAttemptServiceTest {

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        this.clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneId.of("UTC"));
    }

    @Test
    void recordFailure_shouldLockByUsername_whenThresholdReached() {
        LoginAttemptService service = new LoginAttemptService(
                10, 3, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), false, clock);

        assertFalse(service.recordFailure("alice", "10.0.0.1").blocked());
        assertFalse(service.recordFailure("alice", "10.0.0.2").blocked());

        LoginAttemptService.LoginThrottleStatus locked = service.recordFailure("alice", "10.0.0.3");
        assertTrue(locked.blocked());
        assertTrue(locked.retryAfterSeconds() >= 1);

        assertTrue(service.checkBlocked("alice", "10.0.0.9").blocked());
        assertFalse(service.checkBlocked("bob", "10.0.0.9").blocked());
    }

    @Test
    void recordFailure_shouldLockByIp_whenThresholdReached() {
        LoginAttemptService service = new LoginAttemptService(
                2, 10, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), false, clock);

        assertFalse(service.recordFailure("alice", "192.168.1.100").blocked());
        LoginAttemptService.LoginThrottleStatus locked = service.recordFailure("bob", "192.168.1.100");
        assertTrue(locked.blocked());

        assertTrue(service.checkBlocked("charlie", "192.168.1.100").blocked());
        assertFalse(service.checkBlocked("charlie", "192.168.1.101").blocked());
    }

    @Test
    void recordSuccess_shouldClearUserAttempts_only() {
        LoginAttemptService service = new LoginAttemptService(
                3, 2, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), false, clock);

        assertFalse(service.recordFailure("alice", "127.0.0.1").blocked());
        service.recordSuccess("alice");

        // User counter is reset by success, but shared IP counter remains.
        assertFalse(service.recordFailure("alice", "127.0.0.1").blocked());
        assertTrue(service.recordFailure("bob", "127.0.0.1").blocked());
    }

    @Test
    void checkBlocked_shouldRecoverAfterLockDuration() {
        LoginAttemptService service = new LoginAttemptService(
                10, 2, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), false, clock);

        service.recordFailure("alice", "10.0.0.1");
        assertTrue(service.recordFailure("alice", "10.0.0.1").blocked());
        assertTrue(service.checkBlocked("alice", "10.0.0.1").blocked());

        clock.advance(Duration.ofMinutes(16));

        assertFalse(service.checkBlocked("alice", "10.0.0.1").blocked());
    }

    @Test
    void extractClientIp_shouldPreferForwardedHeaders() {
        LoginAttemptService service = new LoginAttemptService(
                20, 8, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), true, Set.of("127.0.0.1"), clock);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 10.0.0.1");
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("203.0.113.1", service.extractClientIp(request));
    }

    @Test
    void extractClientIp_shouldUseRemoteAddr_whenForwardHeadersNotTrusted() {
        LoginAttemptService service = new LoginAttemptService(
                20, 8, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), false, clock);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1, 10.0.0.1");
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("127.0.0.1", service.extractClientIp(request));
    }

    @Test
    void extractClientIp_shouldUseRemoteAddr_whenRequesterIsNotTrustedProxy() {
        LoginAttemptService service = new LoginAttemptService(
                20, 8, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), true, Set.of("10.0.0.1"), clock);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1");
        when(request.getHeader("X-Real-IP")).thenReturn("198.51.100.1");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("127.0.0.1", service.extractClientIp(request));
    }

    @Test
    void extractClientIp_shouldIgnoreInvalidForwardedIpValues() {
        LoginAttemptService service = new LoginAttemptService(
                20, 8, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), true, Set.of("127.0.0.1"), clock);

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("invalid-ip, still.bad");
        when(request.getHeader("X-Real-IP")).thenReturn("also.bad");
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");

        assertEquals("127.0.0.1", service.extractClientIp(request));
    }

    @Test
    void recordFailure_shouldEvictOldEntries_whenUserCapacityExceeded() {
        LoginAttemptService service = new LoginAttemptService(
                20, 2, 100, 2, Duration.ofMinutes(10), Duration.ofMinutes(15), false, clock);

        assertFalse(service.recordFailure("u1", "10.0.0.1").blocked());
        clock.advance(Duration.ofSeconds(1));
        assertFalse(service.recordFailure("u2", "10.0.0.2").blocked());
        clock.advance(Duration.ofSeconds(1));
        assertFalse(service.recordFailure("u3", "10.0.0.3").blocked());

        // If u1 was evicted as oldest entry, this should behave as first failure again.
        assertFalse(service.recordFailure("u1", "10.0.0.4").blocked());
    }

    @Test
    void recordFailure_shouldGroupInvalidIpsAsUnknown() {
        LoginAttemptService service = new LoginAttemptService(
                2, 10, 100, 100, Duration.ofMinutes(10), Duration.ofMinutes(15), false, clock);

        assertFalse(service.recordFailure("alice", "not-an-ip").blocked());
        assertTrue(service.recordFailure("bob", "still-not-ip").blocked());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration duration) {
            this.instant = this.instant.plus(duration);
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
