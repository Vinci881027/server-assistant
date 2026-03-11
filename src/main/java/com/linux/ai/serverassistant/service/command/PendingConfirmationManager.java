package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UsernameUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Shared pending-confirmation manager across command-related services.
 *
 * Key format: {@code username:scope:command}
 * Examples:
 * - {@code alice:cmd:rm -rf /tmp/a}
 * - {@code alice:offload:offload-confirm --source /a --target /b}
 * - {@code alice:mount:mount-confirm --device /dev/sdc --target /mnt/data ...}
 *
 * Thread safety: a single {@code lock} object serializes all mutations and
 * compound read-modify-write operations across registries and payloadStore.
 * PendingConfirmationRegistry is not thread-safe on its own; this class is the
 * sole owner of all registry instances.
 */
@Service
public class PendingConfirmationManager {

    private static final long DEFAULT_TTL_MS = 600_000L;

    private final Object lock = new Object();
    private final Map<PendingConfirmationScope, PendingConfirmationRegistry> registries;
    private final Set<PendingConfirmationRegistry> uniqueRegistries;
    private final PendingConfirmationRegistry defaultRegistry;
    private final HashMap<String, Object> payloadStore = new HashMap<>();

    public PendingConfirmationManager() {
        this(createRegistries(DEFAULT_TTL_MS));
    }

    @Autowired
    public PendingConfirmationManager(
            @Value("${app.command.pending-confirmation.ttl-ms:600000}") long ttlMs) {
        this(createRegistries(Math.max(1L, ttlMs)));
    }

    public PendingConfirmationManager(PendingConfirmationRegistry registry) {
        this(
                registry,
                new PendingConfirmationRegistry(
                        DEFAULT_TTL_MS,
                        PendingConfirmationScope.USER_ADD.maxSize()));
    }

    PendingConfirmationManager(
            PendingConfirmationRegistry registry,
            PendingConfirmationRegistry userAddRegistry) {
        this(createRegistries(registry, userAddRegistry));
    }

    private PendingConfirmationManager(Map<PendingConfirmationScope, PendingConfirmationRegistry> registries) {
        this.registries = Map.copyOf(registries);
        this.uniqueRegistries = Set.copyOf(new HashSet<>(this.registries.values()));
        PendingConfirmationRegistry fallback = this.registries.get(PendingConfirmationScope.CMD);
        this.defaultRegistry = fallback != null ? fallback : this.uniqueRegistries.iterator().next();
    }

    public void store(PendingConfirmationScope scope, String username, String command) {
        if (scope == null || command == null || command.isBlank()) return;
        String key = buildKey(scope, username, command.trim());
        PendingConfirmationRegistry targetRegistry = registryForScope(scope);
        Object payloadToClear = withLock(() -> {
            targetRegistry.put(key);
            return payloadStore.remove(key);
        });
        clearPayload(payloadToClear);
    }

    public boolean consume(PendingConfirmationScope scope, String username, String command) {
        if (scope == null || command == null || command.isBlank()) return false;
        String key = buildKey(scope, username, command.trim());
        PendingConfirmationRegistry targetRegistry = registryForScope(scope);
        ConsumeResult result = withLock(() ->
                new ConsumeResult(targetRegistry.consume(key), payloadStore.remove(key)));
        clearPayload(result.payloadToClear());
        return result.consumed();
    }

    public boolean has(PendingConfirmationScope scope, String username, String command) {
        if (scope == null || command == null || command.isBlank()) return false;
        String key = buildKey(scope, username, command.trim());
        PendingConfirmationRegistry targetRegistry = registryForScope(scope);
        PresenceResult result = withLock(() -> {
            boolean present = targetRegistry.peek(key) != null;
            Object payloadToClear = present ? null : payloadStore.remove(key);
            return new PresenceResult(present, payloadToClear);
        });
        clearPayload(result.payloadToClear());
        return result.present();
    }

    public void clear(PendingConfirmationScope scope, String username, String command) {
        if (scope == null || command == null || command.isBlank()) return;
        String key = buildKey(scope, username, command.trim());
        PendingConfirmationRegistry targetRegistry = registryForScope(scope);
        Object payloadToClear = withLock(() -> {
            targetRegistry.remove(key);
            return payloadStore.remove(key);
        });
        clearPayload(payloadToClear);
    }

    public void storeWithPayload(PendingConfirmationScope scope, String username, String command, Object payload) {
        if (scope == null || command == null || command.isBlank()) {
            clearPayload(payload);
            return;
        }
        String key = buildKey(scope, username, command.trim());
        PendingConfirmationRegistry targetRegistry = registryForScope(scope);
        StoreWithPayloadResult result = withLock(() -> {
            targetRegistry.put(key);
            if (targetRegistry.peek(key) == null) {
                return new StoreWithPayloadResult(payload, payloadStore.remove(key));
            }
            return new StoreWithPayloadResult(null, payloadStore.put(key, payload));
        });
        clearPayload(result.inputPayloadToClear());
        clearPayload(result.replacedPayloadToClear());
    }

    public <T> Optional<T> consumePayload(
            PendingConfirmationScope scope,
            String username,
            String command,
            Class<T> payloadType) {
        if (scope == null
                || command == null || command.isBlank()
                || payloadType == null) {
            return Optional.empty();
        }
        String key = buildKey(scope, username, command.trim());
        PendingConfirmationRegistry targetRegistry = registryForScope(scope);
        ConsumePayloadState state = withLock(() -> {
            if (!targetRegistry.consume(key)) {
                return new ConsumePayloadState(false, payloadStore.remove(key), null);
            }
            return new ConsumePayloadState(true, null, payloadStore.remove(key));
        });
        clearPayload(state.orphanPayloadToClear());
        if (!state.consumed()) {
            return Optional.empty();
        }
        Object payload = state.payload();
        if (payload == null) {
            return Optional.empty();
        }
        if (!payloadType.isInstance(payload)) {
            clearPayload(payload);
            return Optional.empty();
        }
        return Optional.of(payloadType.cast(payload));
    }

    public void clearScopeForUser(PendingConfirmationScope scope, String username) {
        if (scope == null) return;
        clearByPrefix(
                registryForScope(scope),
                UsernameUtils.normalizeUsernameOrAnonymous(username) + ":" + scope.key() + ":");
    }

    public void clearScopesForUser(String username, PendingConfirmationScope... scopes) {
        if (scopes == null || scopes.length == 0) return;
        String normalizedUsername = UsernameUtils.normalizeUsernameOrAnonymous(username);
        for (PendingConfirmationScope scope : scopes) {
            if (scope == null) continue;
            clearByPrefix(registryForScope(scope), normalizedUsername + ":" + scope.key() + ":");
        }
    }

    public void clearAllScopesForUser(String username) {
        clearAcrossAllRegistriesByPrefix(UsernameUtils.normalizeUsernameOrAnonymous(username) + ":");
    }

    public void clearAllScopesForCommand(String username, String command) {
        if (command == null || command.isBlank()) return;
        for (PendingConfirmationScope scope : PendingConfirmationScope.values()) {
            clear(scope, username, command);
        }
    }

    /**
     * Backward-compatible raw key writer.
     *
     * Expected key format: {@code username:scope:command}.
     */
    public void storeRawKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) return;
        int firstSeparator = rawKey.indexOf(':');
        int secondSeparator = rawKey.indexOf(':', firstSeparator + 1);
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1 || secondSeparator >= rawKey.length() - 1) {
            return;
        }
        String username = rawKey.substring(0, firstSeparator);
        String scopeKey = rawKey.substring(firstSeparator + 1, secondSeparator);
        String command = rawKey.substring(secondSeparator + 1).trim();
        if (command.isEmpty()) return;
        store(scopeFromKey(scopeKey), username, command);
    }

    /**
     * Clears all scopes for the provided username and command, then clears the
     * same command under the legacy anonymous fallback identity.
     */
    public void clearAllScopesForCommandWithAnonymousFallback(String username, String command) {
        clearAllScopesForCommand(username, command);
        if (!"anonymous".equals(UsernameUtils.normalizeUsernameOrAnonymous(username))) {
            clearAllScopesForCommand("anonymous", command);
        }
    }

    public void clearAll() {
        List<Object> payloadsToClear = withLock(() -> {
            for (PendingConfirmationRegistry registry : uniqueRegistries) {
                registry.clear();
            }
            return drainAllPayloadsUnderLock();
        });
        clearPayloads(payloadsToClear);
    }

    public Optional<String> getPendingCommandPrompt(String username) {
        String user = UsernameUtils.normalizeUsernameOrAnonymous(username);
        if ("anonymous".equals(user)) return Optional.empty();
        String userPrefix = user + ":" + PendingConfirmationScope.CMD.key() + ":";
        PendingConfirmationRegistry cmdRegistry = registryForScope(PendingConfirmationScope.CMD);
        return withLock(() -> {
            for (String key : cmdRegistry.keysForUsername(user)) {
                if (!key.startsWith(userPrefix)) continue;
                if (!cmdRegistry.markShownIfPending(key)) continue;
                String cmd = key.substring(userPrefix.length());
                return Optional.of(CommandMarkers.confirmationPrompt(cmd));
            }
            return Optional.empty();
        });
    }

    /**
     * Periodically removes expired pending confirmations to avoid sweeping the
     * whole registry on hot read paths.
     */
    @Scheduled(fixedRateString = "${app.command.pending-confirmation.cleanup-interval-ms:60000}")
    public void cleanupExpiredEntries() {
        List<Object> payloadsToClear = withLock(() -> {
            for (PendingConfirmationRegistry registry : uniqueRegistries) {
                registry.purgeExpired();
            }
            return purgeOrphanPayloadsUnderLock();
        });
        clearPayloads(payloadsToClear);
    }

    private static String buildKey(PendingConfirmationScope scope, String username, String command) {
        return UsernameUtils.normalizeUsernameOrAnonymous(username) + ":" + scope.key() + ":" + command;
    }

    private static PendingConfirmationScope scopeFromKey(String scopeKey) {
        if (scopeKey != null) {
            String normalizedScopeKey = scopeKey.trim();
            for (PendingConfirmationScope scope : PendingConfirmationScope.values()) {
                if (scope.key().equalsIgnoreCase(normalizedScopeKey)) {
                    return scope;
                }
            }
        }
        return PendingConfirmationScope.CMD;
    }

    private void clearByPrefix(PendingConfirmationRegistry targetRegistry, String prefix) {
        List<Object> payloadsToClear = withLock(() -> {
            targetRegistry.removeByPrefix(prefix);
            return removePayloadsByPrefixUnderLock(prefix);
        });
        clearPayloads(payloadsToClear);
    }

    private void clearAcrossAllRegistriesByPrefix(String prefix) {
        List<Object> payloadsToClear = withLock(() -> {
            for (PendingConfirmationRegistry registry : uniqueRegistries) {
                registry.removeByPrefix(prefix);
            }
            return removePayloadsByPrefixUnderLock(prefix);
        });
        clearPayloads(payloadsToClear);
    }

    private List<Object> removePayloadsByPrefixUnderLock(String prefix) {
        List<Object> payloadsToClear = new ArrayList<>();
        payloadStore.entrySet().removeIf(entry -> {
            if (!entry.getKey().startsWith(prefix)) {
                return false;
            }
            payloadsToClear.add(entry.getValue());
            return true;
        });
        return payloadsToClear;
    }

    private List<Object> drainAllPayloadsUnderLock() {
        if (payloadStore.isEmpty()) {
            return List.of();
        }
        List<Object> payloadsToClear = new ArrayList<>(payloadStore.values());
        payloadStore.clear();
        return payloadsToClear;
    }

    private List<Object> purgeOrphanPayloadsUnderLock() {
        if (payloadStore.isEmpty()) {
            return List.of();
        }
        List<Object> payloadsToClear = new ArrayList<>();
        Set<String> liveKeys = new HashSet<>();
        for (PendingConfirmationRegistry registry : uniqueRegistries) {
            for (var entry : registry.entries()) {
                liveKeys.add(entry.getKey());
            }
        }
        payloadStore.entrySet().removeIf(entry -> {
            if (liveKeys.contains(entry.getKey())) {
                return false;
            }
            payloadsToClear.add(entry.getValue());
            return true;
        });
        return payloadsToClear;
    }

    private void clearPayloads(List<Object> payloads) {
        for (Object payload : payloads) {
            clearPayload(payload);
        }
    }

    private void clearPayload(Object payload) {
        if (payload instanceof SensitivePendingPayload sensitivePendingPayload) {
            sensitivePendingPayload.clearSensitiveData();
        }
    }

    private PendingConfirmationRegistry registryForScope(PendingConfirmationScope scope) {
        PendingConfirmationRegistry registry = scope == null ? null : registries.get(scope);
        if (registry != null) {
            return registry;
        }
        return defaultRegistry;
    }

    private <T> T withLock(Supplier<T> action) {
        synchronized (lock) {
            return action.get();
        }
    }

    private static Map<PendingConfirmationScope, PendingConfirmationRegistry> createRegistries(long ttlMs) {
        Map<Integer, PendingConfirmationRegistry> registryByMaxSize = new HashMap<>();
        Map<PendingConfirmationScope, PendingConfirmationRegistry> scopedRegistries =
                new EnumMap<>(PendingConfirmationScope.class);
        for (PendingConfirmationScope scope : PendingConfirmationScope.values()) {
            PendingConfirmationRegistry registry =
                    registryByMaxSize.computeIfAbsent(
                            scope.maxSize(),
                            ignored -> new PendingConfirmationRegistry(ttlMs, scope.maxSize()));
            scopedRegistries.put(scope, registry);
        }
        return scopedRegistries;
    }

    private static Map<PendingConfirmationScope, PendingConfirmationRegistry> createRegistries(
            PendingConfirmationRegistry defaultRegistry,
            PendingConfirmationRegistry userAddRegistry) {
        Map<PendingConfirmationScope, PendingConfirmationRegistry> scopedRegistries =
                new EnumMap<>(PendingConfirmationScope.class);
        for (PendingConfirmationScope scope : PendingConfirmationScope.values()) {
            scopedRegistries.put(
                    scope,
                    scope == PendingConfirmationScope.USER_ADD ? userAddRegistry : defaultRegistry);
        }
        return scopedRegistries;
    }

    private record ConsumeResult(boolean consumed, Object payloadToClear) {
    }

    private record PresenceResult(boolean present, Object payloadToClear) {
    }

    private record StoreWithPayloadResult(Object inputPayloadToClear, Object replacedPayloadToClear) {
    }

    private record ConsumePayloadState(boolean consumed, Object orphanPayloadToClear, Object payload) {
    }
}
