package com.linux.ai.serverassistant.util;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.function.Supplier;

/**
 * User context utility.
 *
 * Authenticated identity must come from HttpSession or one of two session maps:
 *
 * <ul>
 *   <li><b>toolSessions</b> — short-lived, per-request UUID keys. Registered by
 *       {@link #registerToolSession} for AI tool threads, background jobs, and any
 *       execution path that runs outside the HTTP request thread.</li>
 *   <li><b>conversationSessions</b> — conversationId-keyed fallback entries. Registered
 *       by {@link #registerConversationSession}. The same conversationId may be active
 *       in multiple concurrent requests, so entries are reference-counted and include
 *       collision detection to guard against two different users binding the same key.</li>
 * </ul>
 *
 * CURRENT_CONTEXT_KEY is thread-scoped and always holds a <em>tool context key</em>.
 * It must never hold a conversationId.
 */
@Component
public class UserContext {
    private static final long DEFAULT_SESSION_TTL_MS = 5 * 60 * 1_000L;
    private static final long DEFAULT_SESSION_HARD_TTL_MS = 60 * 60 * 1_000L;
    private static final long DEFAULT_CLEANUP_INTERVAL_MINUTES = 5L;

    private final ThreadLocal<String> currentContextKey = new ThreadLocal<>();
    private final UserContextStore userContextStore;

    public record ActiveSession(String username, String sessionId, int activeRequests, long createdAt, long lastAccessedAt) {
        public ActiveSession(String username, String sessionId) {
            this(username, sessionId, 1, System.currentTimeMillis(), System.currentTimeMillis());
        }

        public ActiveSession(String username, String sessionId, int activeRequests) {
            this(username, sessionId, activeRequests, System.currentTimeMillis(), System.currentTimeMillis());
        }

        public ActiveSession touch(long now) {
            return new ActiveSession(username, sessionId, activeRequests, createdAt, now);
        }
    }

    public record ResolvedIdentity(String username, String sessionId) {}

    /**
     * Constructor used by Spring. Backend store is selected by configuration.
     */
    @Autowired
    public UserContext(UserContextStore userContextStore) {
        this.userContextStore = userContextStore;
    }

    /**
     * Default constructor for non-Spring tests.
     */
    public UserContext() {
        this(new InMemoryUserContextStore(
                DEFAULT_SESSION_TTL_MS,
                DEFAULT_SESSION_HARD_TTL_MS,
                DEFAULT_CLEANUP_INTERVAL_MINUTES));
    }

    @PostConstruct
    void startStore() {
        userContextStore.start();
    }

    @PreDestroy
    void stopStore() {
        userContextStore.stop();
        clearCurrentContextKey();
    }

    public void setCurrentContextKey(String contextKey) {
        if (contextKey == null || contextKey.isBlank()) {
            currentContextKey.remove();
            return;
        }
        currentContextKey.set(contextKey);
    }

    public String getCurrentContextKey() {
        return currentContextKey.get();
    }

    public void clearCurrentContextKey() {
        currentContextKey.remove();
    }

    /**
     * Runs an action under the given context key and restores the previous key afterwards.
     */
    public <T> T withContextKey(String contextKey, Supplier<T> action) {
        String previous = getCurrentContextKey();
        setCurrentContextKey(contextKey);
        try {
            return action.get();
        } finally {
            if (previous == null || previous.isBlank()) {
                clearCurrentContextKey();
            } else {
                setCurrentContextKey(previous);
            }
        }
    }

    /**
     * Resolves username/session identity using a shared fallback chain.
     * Priority: HttpSession -> explicit username
     * -> toolSessions(current context key) -> conversationSessions(fallback conversation id).
     */
    public ResolvedIdentity resolveIdentity(String explicitUsername, String fallbackConversationId) {
        ResolvedIdentity sessionIdentity = resolveFromHttpSession();
        String username = resolveUsername(explicitUsername, fallbackConversationId, false, sessionIdentity.username());
        String sessionId = firstNonBlank(
                sessionIdentity.sessionId(),
                resolveSessionIdFromActiveSessions(),
                resolveSessionIdFromConversationSession(fallbackConversationId));
        return new ResolvedIdentity(username, sessionId);
    }

    public ResolvedIdentity resolveIdentity(String explicitUsername) {
        return resolveIdentity(explicitUsername, null);
    }

    /**
     * Resolves username with session-first priority.
     *
     * Suitable for authenticated execution paths where servlet-session identity
     * must dominate an optional caller-provided username hint.
     */
    public String resolveUsernameOrAnonymous(String explicitUsername, String fallbackConversationId) {
        return resolveUsernameOrAnonymous(explicitUsername, fallbackConversationId, false);
    }

    /**
     * Resolves username with explicit value first.
     *
     * Suitable for persistence/memory-write paths where a method parameter is
     * the authoritative actor and must not be overridden by an incidental session user.
     *
     * Priority: explicit username -> HttpSession
     * -> toolSessions(current context key) -> conversationSessions(fallback conversation id).
     */
    public String resolveUsernameOrAnonymousPreferExplicit(String explicitUsername, String fallbackConversationId) {
        return resolveUsernameOrAnonymous(explicitUsername, fallbackConversationId, true);
    }

    public String resolveUsernameOrAnonymous(
            String explicitUsername,
            String fallbackConversationId,
            boolean preferExplicitUsername) {
        String sessionUser = resolveFromHttpSession().username();
        String username = resolveUsername(explicitUsername, fallbackConversationId, preferExplicitUsername, sessionUser);
        return username != null ? username : "anonymous";
    }

    public String resolveUsernameOrAnonymous() {
        return resolveUsernameOrAnonymous(null, null);
    }

    /**
     * Normalizes a username value to a non-blank key-safe form.
     *
     * @return trimmed username or {@code "anonymous"} when null/blank
     */
    public String normalizeUsernameOrAnonymous(String username) {
        return UsernameUtils.normalizeUsernameOrAnonymous(username);
    }

    // ========== Tool sessions (per-request UUID keys, no ref counting) ==========

    /**
     * Registers a tool context key bound to the given user for cross-thread identity resolution.
     */
    public void registerToolSession(String toolKey, String username, String sessionId) {
        userContextStore.registerToolSession(toolKey, username, sessionId);
    }

    /**
     * Releases a tool context key registration.
     */
    public void releaseToolSession(String toolKey) {
        userContextStore.releaseToolSession(toolKey);
    }

    /**
     * Resolves username from toolSessions using the current thread-local context key.
     */
    public String resolveFromActiveSessions() {
        String contextKey = getCurrentContextKey();
        if (contextKey == null || contextKey.isBlank()) {
            return null;
        }
        return resolveUsernameFromToolSession(contextKey);
    }

    /**
     * Resolves username from toolSessions for an explicit tool context key.
     */
    public String resolveFromActiveSessions(String toolKey) {
        return resolveUsernameFromToolSession(toolKey);
    }

    /**
     * Resolves username from toolSessions for an explicit tool context key.
     */
    public String resolveUsernameFromToolSession(String toolKey) {
        return userContextStore.resolveUsernameFromToolSession(toolKey);
    }

    /**
     * Resolves session ID from toolSessions using the current thread-local context key.
     */
    public String resolveSessionIdFromActiveSessions() {
        String contextKey = getCurrentContextKey();
        if (contextKey == null || contextKey.isBlank()) {
            return null;
        }
        return resolveSessionIdFromToolSession(contextKey);
    }

    /**
     * Resolves session ID from toolSessions for an explicit tool context key.
     */
    public String resolveSessionIdFromActiveSessions(String toolKey) {
        return resolveSessionIdFromToolSession(toolKey);
    }

    private String resolveSessionIdFromToolSession(String toolKey) {
        return userContextStore.resolveSessionIdFromToolSession(toolKey);
    }

    // ========== Conversation sessions (conversationId fallback, ref-counted) ==========

    /**
     * Registers a conversationId-keyed fallback session.
     */
    public void registerConversationSession(String conversationId, String username, String sessionId) {
        userContextStore.registerConversationSession(conversationId, username, sessionId);
    }

    public void registerConversationSession(String conversationId, String username) {
        registerConversationSession(conversationId, username, null);
    }

    /**
     * Decrements the reference count for a conversationId-keyed session, removing it when it reaches zero.
     */
    public void releaseConversationSession(String conversationId) {
        userContextStore.releaseConversationSession(conversationId);
    }

    /**
     * Resolves username from conversationSessions for the given conversationId.
     */
    public String resolveUsernameFromConversationSession(String conversationId) {
        return userContextStore.resolveUsernameFromConversationSession(conversationId);
    }

    private String resolveSessionIdFromConversationSession(String conversationId) {
        return userContextStore.resolveSessionIdFromConversationSession(conversationId);
    }

    // ========== Housekeeping ==========

    /**
     * Clears all active sessions from both maps. Primarily intended for test isolation.
     */
    public void clearAllActiveSessions() {
        userContextStore.clearAllActiveSessions();
    }

    /**
     * Backward-compatible alias retained for test code.
     */
    public void shutdownCleanupScheduler() {
        userContextStore.stop();
    }

    /**
     * Returns the total number of active sessions across both maps.
     */
    public int activeSessionCount() {
        return userContextStore.activeSessionCount();
    }

    /**
     * Returns a single tool session entry for diagnostics/testing.
     */
    public ActiveSession peekActiveSession(String toolKey) {
        return userContextStore.peekActiveSession(toolKey);
    }

    /**
     * Returns an immutable snapshot of current tool sessions.
     */
    public Map<String, ActiveSession> activeSessionsSnapshot() {
        return userContextStore.activeSessionsSnapshot();
    }

    private ResolvedIdentity resolveFromHttpSession() {
        try {
            ServletRequestAttributes attr = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpSession session = (attr != null) ? attr.getRequest().getSession(false) : null;
            if (session == null) {
                return new ResolvedIdentity(null, null);
            }
            Object rawUser = session.getAttribute("user");
            String username = (rawUser instanceof String str) ? trimToNull(str) : null;
            String sessionId = trimToNull(session.getId());
            return new ResolvedIdentity(username, sessionId);
        } catch (Exception ignored) {
            return new ResolvedIdentity(null, null);
        }
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String firstNonBlankNonAnonymous(String... values) {
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null && !"anonymous".equalsIgnoreCase(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String resolveUsername(
            String explicitUsername,
            String fallbackConversationId,
            boolean preferExplicitUsername,
            String sessionUsername) {
        if (preferExplicitUsername) {
            return firstNonBlankNonAnonymous(
                    explicitUsername,
                    sessionUsername,
                    resolveFromActiveSessions(),
                    resolveUsernameFromConversationSession(fallbackConversationId));
        }
        return firstNonBlankNonAnonymous(
                sessionUsername,
                explicitUsername,
                resolveFromActiveSessions(),
                resolveUsernameFromConversationSession(fallbackConversationId));
    }
}
