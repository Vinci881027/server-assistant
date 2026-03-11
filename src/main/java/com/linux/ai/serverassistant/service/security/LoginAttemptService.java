package com.linux.ai.serverassistant.service.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * In-memory login brute-force protection.
 *
 * Tracks failed login attempts by username and client IP.
 * Applies temporary lockout after too many failures in a time window.
 */
@Service
public class LoginAttemptService {
    private static final Pattern IPV4_LITERAL = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}$");
    private static final Pattern IPV6_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final int maxFailedAttemptsPerIp;
    private final int maxFailedAttemptsPerUser;
    private final int maxTrackedIps;
    private final int maxTrackedUsers;
    private final long failureWindowMs;
    private final long lockDurationMs;
    private final boolean trustForwardHeaders;
    private final Set<String> trustedProxyIps;
    private final Clock clock;

    private final Map<String, AttemptState> ipAttempts = new ConcurrentHashMap<>();
    private final Map<String, AttemptState> userAttempts = new ConcurrentHashMap<>();

    // Per-map locks allow user-tracking and IP-tracking to proceed independently.
    // Always acquire userLock before ipLock (consistent ordering) to prevent deadlock.
    private final Object userLock = new Object();
    private final Object ipLock = new Object();

    @Autowired
    public LoginAttemptService(
            @Value("${app.security.login.max-failed-attempts-per-ip:20}") int maxFailedAttemptsPerIp,
            @Value("${app.security.login.max-failed-attempts-per-user:8}") int maxFailedAttemptsPerUser,
            @Value("${app.security.login.max-tracked-ips:20000}") int maxTrackedIps,
            @Value("${app.security.login.max-tracked-users:20000}") int maxTrackedUsers,
            @Value("${app.security.login.failure-window-minutes:10}") long failureWindowMinutes,
            @Value("${app.security.login.lock-minutes:15}") long lockMinutes,
            @Value("${app.security.login.trust-forward-headers:false}") boolean trustForwardHeaders,
            @Value("${app.security.login.trusted-proxy-ips:}") String trustedProxyIps) {
        this(
                maxFailedAttemptsPerIp,
                maxFailedAttemptsPerUser,
                maxTrackedIps,
                maxTrackedUsers,
                Duration.ofMinutes(failureWindowMinutes),
                Duration.ofMinutes(lockMinutes),
                trustForwardHeaders,
                parseTrustedProxyIps(trustedProxyIps),
                Clock.systemUTC()
        );
    }

    LoginAttemptService(
            int maxFailedAttemptsPerIp,
            int maxFailedAttemptsPerUser,
            int maxTrackedIps,
            int maxTrackedUsers,
            Duration failureWindow,
            Duration lockDuration,
            boolean trustForwardHeaders,
            Clock clock) {
        this(
                maxFailedAttemptsPerIp,
                maxFailedAttemptsPerUser,
                maxTrackedIps,
                maxTrackedUsers,
                failureWindow,
                lockDuration,
                trustForwardHeaders,
                Set.of(),
                clock
        );
    }

    LoginAttemptService(
            int maxFailedAttemptsPerIp,
            int maxFailedAttemptsPerUser,
            int maxTrackedIps,
            int maxTrackedUsers,
            Duration failureWindow,
            Duration lockDuration,
            boolean trustForwardHeaders,
            Set<String> trustedProxyIps,
            Clock clock) {
        if (maxFailedAttemptsPerIp <= 0 || maxFailedAttemptsPerUser <= 0
                || maxTrackedIps <= 0 || maxTrackedUsers <= 0) {
            throw new IllegalArgumentException("attempt thresholds and max tracked entries must be > 0");
        }
        if (failureWindow == null || failureWindow.isZero() || failureWindow.isNegative()) {
            throw new IllegalArgumentException("failure window must be > 0");
        }
        if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
            throw new IllegalArgumentException("lock duration must be > 0");
        }
        this.maxFailedAttemptsPerIp = maxFailedAttemptsPerIp;
        this.maxFailedAttemptsPerUser = maxFailedAttemptsPerUser;
        this.maxTrackedIps = maxTrackedIps;
        this.maxTrackedUsers = maxTrackedUsers;
        this.failureWindowMs = failureWindow.toMillis();
        this.lockDurationMs = lockDuration.toMillis();
        this.trustForwardHeaders = trustForwardHeaders;
        this.trustedProxyIps = trustedProxyIps == null ? Set.of() : Set.copyOf(trustedProxyIps);
        this.clock = clock;
    }

    /**
     * Checks whether current username/IP is temporarily blocked.
     */
    public LoginThrottleStatus checkBlocked(String username, String clientIp) {
        long now = now();
        LoginThrottleStatus userStatus;
        LoginThrottleStatus ipStatus;
        synchronized (userLock) {
            userStatus = getBlockStatus(userAttempts, normalizeUsername(username), now);
        }
        synchronized (ipLock) {
            ipStatus = getBlockStatus(ipAttempts, normalizeIp(clientIp), now);
        }
        return stricter(userStatus, ipStatus);
    }

    /**
     * Records a failed login and returns whether this failure causes lockout.
     */
    public LoginThrottleStatus recordFailure(String username, String clientIp) {
        long now = now();
        LoginThrottleStatus userStatus;
        LoginThrottleStatus ipStatus;
        synchronized (userLock) {
            userStatus = applyFailure(
                    userAttempts, normalizeUsername(username), maxFailedAttemptsPerUser, maxTrackedUsers, now);
        }
        synchronized (ipLock) {
            ipStatus = applyFailure(
                    ipAttempts, normalizeIp(clientIp), maxFailedAttemptsPerIp, maxTrackedIps, now);
        }
        return stricter(userStatus, ipStatus);
    }

    /**
     * Clears per-user attempt records after successful login.
     * Per-IP counters are intentionally retained to avoid bypassing IP throttling
     * by logging in once with another valid account from the same source IP.
     */
    public void recordSuccess(String username) {
        String userKey = normalizeUsername(username);
        if (userKey != null) {
            synchronized (userLock) {
                userAttempts.remove(userKey);
            }
        }
    }

    /**
     * Extracts the client IP from common proxy headers.
     * Only trusts forwarded headers when the immediate peer is a configured trusted proxy.
     */
    public String extractClientIp(HttpServletRequest request) {
        if (request == null) return "unknown";

        String remoteAddr = normalizeIpLiteral(request.getRemoteAddr());
        if (trustForwardHeaders && isTrustedProxy(remoteAddr)) {
            String xForwardedForIp = extractFirstValidForwardedIp(request.getHeader("X-Forwarded-For"));
            if (xForwardedForIp != null) {
                return xForwardedForIp;
            }

            String xRealIp = normalizeIpLiteral(request.getHeader("X-Real-IP"));
            if (xRealIp != null) {
                return xRealIp;
            }
        }

        return remoteAddr == null ? "unknown" : remoteAddr;
    }

    /**
     * Periodically removes stale entries to bound memory usage.
     */
    @Scheduled(fixedRateString = "${app.security.login.cleanup-interval-ms:60000}")
    public void cleanupExpiredEntries() {
        long now = now();
        synchronized (userLock) {
            cleanupMap(userAttempts, now);
        }
        synchronized (ipLock) {
            cleanupMap(ipAttempts, now);
        }
    }

    private void cleanupMap(Map<String, AttemptState> attempts, long now) {
        attempts.entrySet().removeIf(entry -> {
            AttemptState state = entry.getValue();
            if (state == null) return true;
            boolean lockExpired = state.lockedUntilMs <= now;
            boolean windowExpired = (now - state.windowStartMs) >= failureWindowMs;
            return lockExpired && windowExpired;
        });
    }

    private LoginThrottleStatus getBlockStatus(Map<String, AttemptState> attempts, String key, long now) {
        if (key == null) return LoginThrottleStatus.allowed();

        AttemptState state = attempts.get(key);
        if (state == null) return LoginThrottleStatus.allowed();

        refreshState(state, now);
        if (state.lockedUntilMs > now) {
            return LoginThrottleStatus.blocked(toRetrySeconds(state.lockedUntilMs - now));
        }
        return LoginThrottleStatus.allowed();
    }

    private LoginThrottleStatus applyFailure(
            Map<String, AttemptState> attempts,
            String key,
            int threshold,
            int maxTrackedEntries,
            long now) {
        if (key == null) return LoginThrottleStatus.allowed();

        AttemptState state = attempts.get(key);
        if (state == null) {
            ensureCapacity(attempts, maxTrackedEntries, now);
            state = attempts.computeIfAbsent(key, k -> new AttemptState(now));
        }
        refreshState(state, now);

        if (state.lockedUntilMs > now) {
            return LoginThrottleStatus.blocked(toRetrySeconds(state.lockedUntilMs - now));
        }

        state.failureCount++;
        if (state.failureCount >= threshold) {
            state.failureCount = 0;
            state.lockedUntilMs = now + lockDurationMs;
            return LoginThrottleStatus.blocked(toRetrySeconds(lockDurationMs));
        }

        return LoginThrottleStatus.allowed();
    }

    private void ensureCapacity(Map<String, AttemptState> attempts, int maxTrackedEntries, long now) {
        if (attempts.size() < maxTrackedEntries) {
            return;
        }
        cleanupMap(attempts, now);
        if (attempts.size() < maxTrackedEntries) {
            return;
        }

        String evictionKey = null;
        long oldestWindowStart = Long.MAX_VALUE;
        boolean selectedUnlocked = false;

        for (Map.Entry<String, AttemptState> entry : attempts.entrySet()) {
            AttemptState state = entry.getValue();
            if (state == null) {
                evictionKey = entry.getKey();
                break;
            }

            boolean unlocked = state.lockedUntilMs <= now;
            if (evictionKey == null
                    || (unlocked && !selectedUnlocked)
                    || (unlocked == selectedUnlocked && state.windowStartMs < oldestWindowStart)) {
                evictionKey = entry.getKey();
                oldestWindowStart = state.windowStartMs;
                selectedUnlocked = unlocked;
            }
        }

        if (evictionKey != null) {
            attempts.remove(evictionKey);
        }
    }

    private void refreshState(AttemptState state, long now) {
        if (state.lockedUntilMs > 0 && state.lockedUntilMs <= now) {
            state.lockedUntilMs = 0;
            state.failureCount = 0;
            state.windowStartMs = now;
            return;
        }

        if ((now - state.windowStartMs) >= failureWindowMs) {
            state.failureCount = 0;
            state.windowStartMs = now;
        }
    }

    private LoginThrottleStatus stricter(LoginThrottleStatus left, LoginThrottleStatus right) {
        if (left.blocked() && right.blocked()) {
            return left.retryAfterSeconds() >= right.retryAfterSeconds() ? left : right;
        }
        if (left.blocked()) return left;
        if (right.blocked()) return right;
        return LoginThrottleStatus.allowed();
    }

    private String normalizeUsername(String username) {
        if (username == null) return null;
        String trimmed = username.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String normalizeIp(String clientIp) {
        String normalized = normalizeIpLiteral(clientIp);
        return normalized == null ? "unknown" : normalized;
    }

    private boolean isTrustedProxy(String remoteAddr) {
        return remoteAddr != null && trustedProxyIps.contains(remoteAddr);
    }

    private String extractFirstValidForwardedIp(String xForwardedFor) {
        if (xForwardedFor == null || xForwardedFor.isBlank()) {
            return null;
        }
        for (String rawIp : xForwardedFor.split(",")) {
            String normalized = normalizeIpLiteral(rawIp);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private static Set<String> parseTrustedProxyIps(String configuredTrustedProxyIps) {
        if (configuredTrustedProxyIps == null || configuredTrustedProxyIps.isBlank()) {
            return Set.of();
        }

        Set<String> parsed = new HashSet<>();
        for (String rawIp : configuredTrustedProxyIps.split(",")) {
            String value = rawIp == null ? "" : rawIp.trim();
            if (value.isBlank()) {
                continue;
            }
            String normalized = normalizeIpLiteral(value);
            if (normalized == null) {
                throw new IllegalArgumentException(
                        "app.security.login.trusted-proxy-ips contains invalid IP: " + value
                );
            }
            parsed.add(normalized);
        }
        return parsed.isEmpty() ? Set.of() : Set.copyOf(parsed);
    }

    private static String normalizeIpLiteral(String rawIp) {
        if (rawIp == null) {
            return null;
        }

        String candidate = rawIp.trim();
        if (candidate.isBlank()) {
            return null;
        }

        if (candidate.startsWith("[") && candidate.endsWith("]") && candidate.length() > 2) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }

        if (!looksLikeIpLiteral(candidate)) {
            return null;
        }

        try {
            return InetAddress.getByName(candidate).getHostAddress();
        } catch (UnknownHostException ex) {
            return null;
        }
    }

    private static boolean looksLikeIpLiteral(String candidate) {
        if (candidate.indexOf(':') >= 0) {
            return IPV6_LITERAL.matcher(candidate).matches();
        }
        return IPV4_LITERAL.matcher(candidate).matches() && isValidIpv4Octets(candidate);
    }

    private static boolean isValidIpv4Octets(String candidate) {
        String[] octets = candidate.split("\\.");
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            int value;
            try {
                value = Integer.parseInt(octet);
            } catch (NumberFormatException ex) {
                return false;
            }
            if (value < 0 || value > 255) {
                return false;
            }
        }
        return true;
    }

    private long now() {
        return clock.millis();
    }

    private long toRetrySeconds(long retryAfterMs) {
        if (retryAfterMs <= 0) return 1;
        return Math.max(1, (retryAfterMs + 999) / 1000);
    }

    private static final class AttemptState {
        private int failureCount;
        private long windowStartMs;
        private long lockedUntilMs;

        private AttemptState(long now) {
            this.failureCount = 0;
            this.windowStartMs = now;
            this.lockedUntilMs = 0;
        }
    }

    public record LoginThrottleStatus(boolean blocked, long retryAfterSeconds) {
        public static LoginThrottleStatus allowed() {
            return new LoginThrottleStatus(false, 0);
        }

        public static LoginThrottleStatus blocked(long retryAfterSeconds) {
            return new LoginThrottleStatus(true, Math.max(1, retryAfterSeconds));
        }
    }
}
