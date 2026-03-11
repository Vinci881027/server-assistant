package com.linux.ai.serverassistant.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Process-local in-memory implementation for UserContextStore.
 */
public class InMemoryUserContextStore implements UserContextStore {
    private final long sessionTtlMs;
    private final long sessionHardTtlMs;
    private final long cleanupIntervalMinutes;

    private final ConcurrentHashMap<String, UserContext.ActiveSession> toolSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UserContext.ActiveSession> conversationSessions = new ConcurrentHashMap<>();

    private final Object cleanupLock = new Object();
    private volatile ScheduledExecutorService cleanupExecutor;

    public InMemoryUserContextStore(long sessionTtlMs, long sessionHardTtlMs, long cleanupIntervalMinutes) {
        this.sessionTtlMs = Math.max(1L, sessionTtlMs);
        this.sessionHardTtlMs = Math.max(1L, sessionHardTtlMs);
        this.cleanupIntervalMinutes = Math.max(1L, cleanupIntervalMinutes);
    }

    @Override
    public void start() {
        ensureCleanupSchedulerStarted();
    }

    @Override
    public void stop() {
        synchronized (cleanupLock) {
            if (cleanupExecutor == null) {
                return;
            }
            cleanupExecutor.shutdownNow();
            cleanupExecutor = null;
        }
    }

    @Override
    public void registerToolSession(String toolKey, String username, String sessionId) {
        if (isBlank(toolKey) || isBlank(username)) {
            return;
        }
        ensureCleanupSchedulerStarted();
        long now = System.currentTimeMillis();
        toolSessions.put(toolKey, new UserContext.ActiveSession(username, sessionId, 1, now, now));
    }

    @Override
    public void releaseToolSession(String toolKey) {
        if (isBlank(toolKey)) {
            return;
        }
        toolSessions.remove(toolKey);
    }

    @Override
    public String resolveUsernameFromToolSession(String toolKey) {
        UserContext.ActiveSession session = getValidToolSession(toolKey);
        if (session == null) {
            return null;
        }
        return trimToNull(session.username());
    }

    @Override
    public String resolveSessionIdFromToolSession(String toolKey) {
        UserContext.ActiveSession session = getValidToolSession(toolKey);
        if (session == null) {
            return null;
        }
        return trimToNull(session.sessionId());
    }

    @Override
    public void registerConversationSession(String conversationId, String username, String sessionId) {
        if (isBlank(conversationId) || isBlank(username)) {
            return;
        }
        ensureCleanupSchedulerStarted();
        long now = System.currentTimeMillis();
        conversationSessions.compute(conversationId, (key, existing) -> {
            if (existing == null || shouldCleanupSession(existing, now)) {
                return new UserContext.ActiveSession(username, sessionId, 1, now, now);
            }
            if (!existing.username().equals(username)) {
                throw new IllegalStateException(
                        "Conversation session collision: key '" + conversationId + "' already bound to user '"
                                + existing.username() + "', attempted by '" + username + "'");
            }
            int next = existing.activeRequests() + 1;
            if (next < 1) {
                next = 1;
            }
            String effectiveSessionId = !isBlank(sessionId) ? sessionId : existing.sessionId();
            return new UserContext.ActiveSession(username, effectiveSessionId, next, existing.createdAt(), now);
        });
    }

    @Override
    public void releaseConversationSession(String conversationId) {
        if (isBlank(conversationId)) {
            return;
        }
        long now = System.currentTimeMillis();
        conversationSessions.computeIfPresent(conversationId, (key, existing) -> {
            if (shouldCleanupSession(existing, now)) {
                return null;
            }
            int next = existing.activeRequests() - 1;
            if (next <= 0) {
                return null;
            }
            return new UserContext.ActiveSession(existing.username(), existing.sessionId(), next, existing.createdAt(), now);
        });
    }

    @Override
    public String resolveUsernameFromConversationSession(String conversationId) {
        return resolveFieldFromConversationSession(conversationId, UserContext.ActiveSession::username);
    }

    @Override
    public String resolveSessionIdFromConversationSession(String conversationId) {
        return resolveFieldFromConversationSession(conversationId, UserContext.ActiveSession::sessionId);
    }

    @Override
    public void clearAllActiveSessions() {
        toolSessions.clear();
        conversationSessions.clear();
    }

    @Override
    public int activeSessionCount() {
        cleanupExpiredSessions();
        return toolSessions.size() + conversationSessions.size();
    }

    @Override
    public UserContext.ActiveSession peekActiveSession(String toolKey) {
        if (isBlank(toolKey)) {
            return null;
        }
        UserContext.ActiveSession session = toolSessions.get(toolKey);
        if (session == null) {
            return null;
        }
        if (shouldCleanupSession(session, System.currentTimeMillis())) {
            toolSessions.remove(toolKey, session);
            return null;
        }
        if (session.activeRequests() <= 0) {
            return null;
        }
        return session;
    }

    @Override
    public Map<String, UserContext.ActiveSession> activeSessionsSnapshot() {
        cleanupExpiredSessions();
        return Map.copyOf(toolSessions);
    }

    private void ensureCleanupSchedulerStarted() {
        ScheduledExecutorService executor = cleanupExecutor;
        if (executor != null && !executor.isShutdown()) {
            return;
        }
        synchronized (cleanupLock) {
            executor = cleanupExecutor;
            if (executor != null && !executor.isShutdown()) {
                return;
            }
            cleanupExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "user-context-cleanup");
                thread.setDaemon(true);
                return thread;
            });
            cleanupExecutor.scheduleAtFixedRate(
                    this::cleanupExpiredSessions,
                    cleanupIntervalMinutes,
                    cleanupIntervalMinutes,
                    TimeUnit.MINUTES
            );
        }
    }

    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        toolSessions.entrySet().removeIf(e -> shouldCleanupSession(e.getValue(), now));
        conversationSessions.entrySet().removeIf(e -> shouldCleanupSession(e.getValue(), now));
    }

    private boolean shouldCleanupSession(UserContext.ActiveSession session, long now) {
        if (session == null) {
            return true;
        }
        long inactiveAgeMs = now - session.lastAccessedAt();
        boolean hardExpired = inactiveAgeMs > sessionHardTtlMs;
        boolean softExpired = inactiveAgeMs > sessionTtlMs;
        return hardExpired || (softExpired && session.activeRequests() <= 0);
    }

    private UserContext.ActiveSession getValidToolSession(String toolKey) {
        if (isBlank(toolKey)) {
            return null;
        }
        UserContext.ActiveSession session = toolSessions.get(toolKey);
        if (session == null) {
            return null;
        }
        if (shouldCleanupSession(session, System.currentTimeMillis())) {
            toolSessions.remove(toolKey, session);
            return null;
        }
        return session;
    }

    private String resolveFieldFromConversationSession(
            String conversationId,
            Function<UserContext.ActiveSession, String> extractor) {
        if (isBlank(conversationId) || extractor == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        UserContext.ActiveSession session = conversationSessions.computeIfPresent(conversationId, (key, existing) -> {
            if (shouldCleanupSession(existing, now)) {
                return null;
            }
            return existing.touch(now);
        });
        if (session == null || session.activeRequests() <= 0) {
            return null;
        }
        return trimToNull(extractor.apply(session));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
