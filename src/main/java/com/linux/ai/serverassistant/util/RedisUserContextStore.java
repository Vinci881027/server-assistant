package com.linux.ai.serverassistant.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Redis-backed UserContextStore with TTL and namespaced keys.
 */
public class RedisUserContextStore implements UserContextStore {
    private static final int TX_MAX_RETRIES = 5;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String namespace;
    private final long sessionHardTtlMs;
    private final Duration sessionHardTtl;

    public RedisUserContextStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            String namespace,
            long sessionHardTtlMs) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.namespace = normalizeNamespace(namespace);
        this.sessionHardTtlMs = Math.max(1L, sessionHardTtlMs);
        this.sessionHardTtl = Duration.ofMillis(this.sessionHardTtlMs);
    }

    @Override
    public void registerToolSession(String toolKey, String username, String sessionId) {
        if (isBlank(toolKey) || isBlank(username)) {
            return;
        }
        long now = System.currentTimeMillis();
        UserContext.ActiveSession session = new UserContext.ActiveSession(username, sessionId, 1, now, now);
        writeSession(toolSessionKey(toolKey), session);
    }

    @Override
    public void releaseToolSession(String toolKey) {
        if (isBlank(toolKey)) {
            return;
        }
        redisTemplate.delete(toolSessionKey(toolKey));
    }

    @Override
    public String resolveUsernameFromToolSession(String toolKey) {
        UserContext.ActiveSession session = readValidSession(toolSessionKey(toolKey));
        if (session == null) {
            return null;
        }
        return trimToNull(session.username());
    }

    @Override
    public String resolveSessionIdFromToolSession(String toolKey) {
        UserContext.ActiveSession session = readValidSession(toolSessionKey(toolKey));
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
        String redisKey = conversationSessionKey(conversationId);
        long now = System.currentTimeMillis();

        updateWithTransaction(redisKey, true, existing -> {
            if (existing == null || isHardExpired(existing, now)) {
                return new UserContext.ActiveSession(username, sessionId, 1, now, now);
            }
            if (!username.equals(existing.username())) {
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
        String redisKey = conversationSessionKey(conversationId);
        long now = System.currentTimeMillis();

        updateWithTransaction(redisKey, false, existing -> {
            if (existing == null || isHardExpired(existing, now)) {
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
        return resolveConversationField(conversationId, UserContext.ActiveSession::username);
    }

    @Override
    public String resolveSessionIdFromConversationSession(String conversationId) {
        return resolveConversationField(conversationId, UserContext.ActiveSession::sessionId);
    }

    @Override
    public void clearAllActiveSessions() {
        Set<String> keys = allNamespaceKeys();
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Override
    public int activeSessionCount() {
        return allNamespaceKeys().size();
    }

    @Override
    public UserContext.ActiveSession peekActiveSession(String toolKey) {
        return readValidSession(toolSessionKey(toolKey));
    }

    @Override
    public Map<String, UserContext.ActiveSession> activeSessionsSnapshot() {
        Set<String> keys = safeKeys(toolSessionPattern());
        if (keys.isEmpty()) {
            return Map.of();
        }
        Map<String, UserContext.ActiveSession> snapshot = new LinkedHashMap<>();
        for (String key : keys) {
            UserContext.ActiveSession session = readValidSession(key);
            if (session == null) {
                continue;
            }
            snapshot.put(extractSuffix(key, ":tool:"), session);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    private void updateWithTransaction(
            String redisKey,
            boolean createIfMissing,
            Function<UserContext.ActiveSession, UserContext.ActiveSession> updater) {
        for (int attempt = 0; attempt < TX_MAX_RETRIES; attempt++) {
            Boolean committed = redisTemplate.execute(new SessionCallback<Boolean>() {
                @SuppressWarnings("unchecked")
                @Override
                public Boolean execute(RedisOperations operations) {
                    operations.watch(redisKey);
                    String raw = (String) operations.opsForValue().get(redisKey);
                    UserContext.ActiveSession existing = deserialize(raw);
                    if (!createIfMissing && existing == null) {
                        operations.unwatch();
                        return true;
                    }

                    UserContext.ActiveSession updated;
                    try {
                        updated = updater.apply(existing);
                    } catch (RuntimeException ex) {
                        operations.unwatch();
                        throw ex;
                    }

                    operations.multi();
                    if (updated == null) {
                        operations.delete(redisKey);
                    } else {
                        operations.opsForValue().set(redisKey, serialize(updated), sessionHardTtl);
                    }
                    List<Object> result = operations.exec();
                    return result != null;
                }
            });

            if (Boolean.TRUE.equals(committed)) {
                return;
            }
        }

        throw new IllegalStateException("Failed to update Redis user context after retries.");
    }

    private String resolveConversationField(
            String conversationId,
            Function<UserContext.ActiveSession, String> extractor) {
        if (isBlank(conversationId) || extractor == null) {
            return null;
        }
        String redisKey = conversationSessionKey(conversationId);

        for (int attempt = 0; attempt < TX_MAX_RETRIES; attempt++) {
            FieldResolveResult result = redisTemplate.execute(new SessionCallback<FieldResolveResult>() {
                @SuppressWarnings("unchecked")
                @Override
                public FieldResolveResult execute(RedisOperations operations) {
                    operations.watch(redisKey);
                    String raw = (String) operations.opsForValue().get(redisKey);
                    UserContext.ActiveSession existing = deserialize(raw);
                    if (existing == null) {
                        operations.unwatch();
                        return FieldResolveResult.done(null, true);
                    }

                    long now = System.currentTimeMillis();
                    if (isHardExpired(existing, now) || existing.activeRequests() <= 0) {
                        operations.multi();
                        operations.delete(redisKey);
                        List<Object> execResult = operations.exec();
                        if (execResult == null) {
                            return FieldResolveResult.retry();
                        }
                        return FieldResolveResult.done(null, true);
                    }

                    UserContext.ActiveSession touched = existing.touch(now);
                    operations.multi();
                    operations.opsForValue().set(redisKey, serialize(touched), sessionHardTtl);
                    List<Object> execResult = operations.exec();
                    if (execResult == null) {
                        return FieldResolveResult.retry();
                    }
                    return FieldResolveResult.done(trimToNull(extractor.apply(touched)), true);
                }
            });

            if (result == null) {
                continue;
            }
            if (result.success) {
                return result.value;
            }
        }
        return null;
    }

    private UserContext.ActiveSession readValidSession(String redisKey) {
        if (isBlank(redisKey)) {
            return null;
        }
        String raw = redisTemplate.opsForValue().get(redisKey);
        UserContext.ActiveSession session = deserialize(raw);
        if (session == null) {
            return null;
        }
        if (isHardExpired(session, System.currentTimeMillis()) || session.activeRequests() <= 0) {
            redisTemplate.delete(redisKey);
            return null;
        }
        return session;
    }

    private boolean isHardExpired(UserContext.ActiveSession session, long now) {
        if (session == null) {
            return true;
        }
        return now - session.lastAccessedAt() > sessionHardTtlMs;
    }

    private Set<String> allNamespaceKeys() {
        return safeKeys(namespace + ":*");
    }

    private Set<String> safeKeys(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        return keys == null ? Set.of() : keys;
    }

    private void writeSession(String redisKey, UserContext.ActiveSession session) {
        redisTemplate.opsForValue().set(redisKey, serialize(session), sessionHardTtl);
    }

    private UserContext.ActiveSession deserialize(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return objectMapper.readValue(value, UserContext.ActiveSession.class);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String serialize(UserContext.ActiveSession session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize user context session for Redis.", ex);
        }
    }

    private String toolSessionKey(String toolKey) {
        if (isBlank(toolKey)) {
            return null;
        }
        return namespace + ":tool:" + toolKey;
    }

    private String conversationSessionKey(String conversationId) {
        if (isBlank(conversationId)) {
            return null;
        }
        return namespace + ":conversation:" + conversationId;
    }

    private String toolSessionPattern() {
        return namespace + ":tool:*";
    }

    private String extractSuffix(String redisKey, String marker) {
        if (isBlank(redisKey) || isBlank(marker)) {
            return redisKey;
        }
        int index = redisKey.indexOf(marker);
        if (index < 0) {
            return redisKey;
        }
        return redisKey.substring(index + marker.length());
    }

    private String normalizeNamespace(String value) {
        if (isBlank(value)) {
            return "server-assistant:user-context";
        }
        String trimmed = value.trim();
        if (trimmed.endsWith(":")) {
            return trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record FieldResolveResult(String value, boolean success) {
        static FieldResolveResult retry() {
            return new FieldResolveResult(null, false);
        }

        static FieldResolveResult done(String value, boolean success) {
            return new FieldResolveResult(value, success);
        }
    }
}
