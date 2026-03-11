package com.linux.ai.serverassistant.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RedisUserContextStoreTest {

    @Test
    void registerToolSession_shouldRoundTripAndAppearInSnapshot() {
        FakeRedisBackend backend = new FakeRedisBackend();
        RedisUserContextStore store = createStore(backend, 60_000L);

        store.registerToolSession("tool-1", "alice", "sid-1");

        assertEquals("alice", store.resolveUsernameFromToolSession("tool-1"));
        assertEquals("sid-1", store.resolveSessionIdFromToolSession("tool-1"));

        Map<String, UserContext.ActiveSession> snapshot = store.activeSessionsSnapshot();
        assertEquals(1, snapshot.size());
        assertTrue(snapshot.containsKey("tool-1"));
        assertEquals(1, store.activeSessionCount());
    }

    @Test
    void releaseToolSession_shouldRemoveEntry() {
        FakeRedisBackend backend = new FakeRedisBackend();
        RedisUserContextStore store = createStore(backend, 60_000L);
        store.registerToolSession("tool-1", "alice", "sid-1");

        store.releaseToolSession("tool-1");

        assertNull(store.resolveUsernameFromToolSession("tool-1"));
        assertEquals(0, store.activeSessionCount());
    }

    @Test
    void conversationSession_shouldReferenceCountAndRelease() {
        FakeRedisBackend backend = new FakeRedisBackend();
        RedisUserContextStore store = createStore(backend, 60_000L);

        store.registerConversationSession("conv-1", "alice", "sid-1");
        store.registerConversationSession("conv-1", "alice", "sid-1");

        store.releaseConversationSession("conv-1");
        assertEquals("alice", store.resolveUsernameFromConversationSession("conv-1"));

        store.releaseConversationSession("conv-1");
        assertNull(store.resolveUsernameFromConversationSession("conv-1"));
    }

    @Test
    void registerConversationSession_differentUser_shouldThrowCollision() {
        FakeRedisBackend backend = new FakeRedisBackend();
        RedisUserContextStore store = createStore(backend, 60_000L);

        store.registerConversationSession("conv-1", "alice", "sid-1");

        assertThrows(IllegalStateException.class,
                () -> store.registerConversationSession("conv-1", "bob", "sid-2"));
    }

    @Test
    void registerToolSession_withExpiredTtl_shouldBeCleanedOnRead() throws Exception {
        FakeRedisBackend backend = new FakeRedisBackend();
        RedisUserContextStore store = createStore(backend, 20L);

        store.registerToolSession("tool-ttl", "alice", "sid-1");
        Thread.sleep(50L);

        assertNull(store.resolveUsernameFromToolSession("tool-ttl"));
        assertEquals(0, store.activeSessionCount());
    }

    @Test
    void clearAllActiveSessions_shouldDeleteNamespacedEntriesOnly() {
        FakeRedisBackend backend = new FakeRedisBackend();
        RedisUserContextStore store = createStore(backend, 60_000L);

        store.registerToolSession("tool-1", "alice", "sid-1");
        backend.directSet("other:namespace:key", "x", Duration.ofMinutes(1));

        store.clearAllActiveSessions();

        assertEquals(0, store.activeSessionCount());
        assertNotNull(backend.getRaw("other:namespace:key"));
    }

    @Test
    void registerConversationSession_whenFirstExecFails_shouldRetryAndSucceed() {
        FakeRedisBackend backend = new FakeRedisBackend();
        backend.failExecTimes(1);
        RedisUserContextStore store = createStore(backend, 60_000L);

        store.registerConversationSession("conv-retry", "alice", "sid-1");

        assertEquals("alice", store.resolveUsernameFromConversationSession("conv-retry"));
    }

    private RedisUserContextStore createStore(FakeRedisBackend backend, long hardTtlMs) {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOps = backend.valueOps();
        RedisOperations<String, String> redisOps = backend.redisOperations();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.keys(anyString())).thenAnswer(inv -> backend.keys(inv.getArgument(0, String.class)));
        when(redisTemplate.delete(anyString())).thenAnswer(inv -> backend.delete(inv.getArgument(0, String.class)));
        when(redisTemplate.delete(anyCollection())).thenAnswer(inv -> backend.deleteAll(inv.getArgument(0)));

        doAnswer(inv -> {
            SessionCallback<?> callback = inv.getArgument(0, SessionCallback.class);
            return callback.execute(redisOps);
        }).when(redisTemplate).execute(any(SessionCallback.class));

        return new RedisUserContextStore(redisTemplate, new ObjectMapper(), "test:user-context", hardTtlMs);
    }

    private static final class FakeRedisBackend {
        private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();

        private final AtomicBoolean inMulti = new AtomicBoolean(false);
        private final AtomicInteger forcedExecFailures = new AtomicInteger(0);
        private final List<Runnable> txOps = new ArrayList<>();

        ValueOperations<String, String> valueOps() {
            InvocationHandler handler = (proxy, method, args) -> handleValueOps(method, args, false);
            return (ValueOperations<String, String>) Proxy.newProxyInstance(
                    ValueOperations.class.getClassLoader(),
                    new Class<?>[]{ValueOperations.class},
                    handler);
        }

        RedisOperations<String, String> redisOperations() {
            InvocationHandler handler = this::handleRedisOps;
            return (RedisOperations<String, String>) Proxy.newProxyInstance(
                    RedisOperations.class.getClassLoader(),
                    new Class<?>[]{RedisOperations.class},
                    handler);
        }

        void directSet(String key, String value, Duration ttl) {
            put(key, value, ttl);
        }

        String getRaw(String key) {
            return get(key);
        }

        void failExecTimes(int count) {
            forcedExecFailures.set(Math.max(0, count));
        }

        Set<String> keys(String pattern) {
            cleanupExpired();
            if (pattern == null || pattern.isBlank()) {
                return Set.of();
            }
            if ("*".equals(pattern)) {
                return new LinkedHashSet<>(data.keySet());
            }
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                Set<String> matched = new LinkedHashSet<>();
                for (String key : data.keySet()) {
                    if (key.startsWith(prefix)) {
                        matched.add(key);
                    }
                }
                return matched;
            }
            return data.containsKey(pattern) ? Set.of(pattern) : Set.of();
        }

        boolean delete(String key) {
            if (key == null) {
                return false;
            }
            return data.remove(key) != null;
        }

        long deleteAll(Collection<String> keys) {
            if (keys == null || keys.isEmpty()) {
                return 0L;
            }
            long deleted = 0L;
            for (String key : keys) {
                if (delete(key)) {
                    deleted++;
                }
            }
            return deleted;
        }

        private Object handleRedisOps(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            switch (name) {
                case "watch", "unwatch" -> {
                    return null;
                }
                case "multi" -> {
                    inMulti.set(true);
                    txOps.clear();
                    return null;
                }
                case "exec" -> {
                    if (!inMulti.get()) {
                        return null;
                    }
                    if (forcedExecFailures.getAndUpdate(v -> Math.max(0, v - 1)) > 0) {
                        txOps.clear();
                        inMulti.set(false);
                        return null;
                    }
                    txOps.forEach(Runnable::run);
                    txOps.clear();
                    inMulti.set(false);
                    return List.of("OK");
                }
                case "opsForValue" -> {
                    InvocationHandler valueHandler = (p, m, a) -> handleValueOps(m, a, true);
                    return Proxy.newProxyInstance(
                            ValueOperations.class.getClassLoader(),
                            new Class<?>[]{ValueOperations.class},
                            valueHandler);
                }
                case "delete" -> {
                    String key = (String) args[0];
                    if (inMulti.get()) {
                        txOps.add(() -> delete(key));
                        return true;
                    }
                    return delete(key);
                }
                default -> {
                    if (method.getReturnType().isPrimitive()) {
                        if (boolean.class.equals(method.getReturnType())) {
                            return false;
                        }
                        if (long.class.equals(method.getReturnType())
                                || int.class.equals(method.getReturnType())
                                || short.class.equals(method.getReturnType())
                                || byte.class.equals(method.getReturnType())) {
                            return 0;
                        }
                        if (double.class.equals(method.getReturnType()) || float.class.equals(method.getReturnType())) {
                            return 0.0;
                        }
                        if (char.class.equals(method.getReturnType())) {
                            return '\0';
                        }
                    }
                    return null;
                }
            }
        }

        private Object handleValueOps(Method method, Object[] args, boolean insideRedisOps) {
            String name = method.getName();
            if ("get".equals(name) && args != null && args.length == 1) {
                return get((String) args[0]);
            }
            if ("set".equals(name) && args != null && args.length == 3 && args[2] instanceof Duration ttl) {
                String key = (String) args[0];
                String value = (String) args[1];
                if (insideRedisOps && inMulti.get()) {
                    txOps.add(() -> put(key, value, ttl));
                } else {
                    put(key, value, ttl);
                }
                return null;
            }
            throw new UnsupportedOperationException("Unsupported ValueOperations method: " + name);
        }

        private void put(String key, String value, Duration ttl) {
            long now = System.currentTimeMillis();
            long expiresAt = now + Math.max(1L, ttl.toMillis());
            data.put(key, new Entry(value, expiresAt));
        }

        private String get(String key) {
            if (key == null) {
                return null;
            }
            Entry entry = data.get(key);
            if (entry == null) {
                return null;
            }
            if (System.currentTimeMillis() > entry.expiresAtMillis()) {
                data.remove(key, entry);
                return null;
            }
            return entry.value();
        }

        private void cleanupExpired() {
            long now = System.currentTimeMillis();
            data.entrySet().removeIf(e -> now > e.getValue().expiresAtMillis());
        }

        private record Entry(String value, long expiresAtMillis) {}
    }
}
