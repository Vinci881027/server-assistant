package com.linux.ai.serverassistant.service.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic pending-confirmation registry with TTL and bounded capacity.
 *
 * Used by CommandExecutionService and SlashCommandRouter to track high-risk
 * operations that are awaiting explicit user confirmation.
 *
 * Thread-safe: all public methods are synchronized on {@code this}.
 * Callers that need atomicity across multiple registries or across a registry
 * and an external payloadStore (e.g., PendingConfirmationManager) must hold
 * their own external lock in addition to the per-instance lock.
 *
 * Keys are fully-formed strings provided by the caller
 * (e.g., "username:cmd:COMMAND", "username:offload:COMMAND").
 *
 * Values track timestamp and prompt state explicitly so the timestamp remains
 * a timestamp only (no overloaded sign-bit encoding).
 */
public class PendingConfirmationRegistry {

    private final Map<String, PendingEntry> store = new HashMap<>();
    private final Map<String, Set<String>> userIndex = new HashMap<>();
    private final long ttlMs;
    private final int maxSize;

    public PendingConfirmationRegistry(long ttlMs, int maxSize) {
        this.ttlMs = ttlMs;
        this.maxSize = maxSize;
    }

    /**
     * Stores an entry for the given key with the current timestamp.
     * If at capacity, expired entries are evicted first; then the oldest entry
     * if still needed. If capacity cannot be freed, the new entry is dropped.
     *
     * @param key fully-formed confirmation key (e.g., "username:cmd:COMMAND")
     */
    public synchronized void put(String key) {
        long now = System.currentTimeMillis();
        if (store.size() >= maxSize) {
            evictExpiredAndOldest(now);
        }
        if (store.size() < maxSize) {
            store.put(key, new PendingEntry(now, false));
            indexKey(key);
        }
        // else: silently drop when capacity is truly exhausted
    }

    /**
     * Atomically removes the entry if it exists and has not expired.
     *
     * @return true if the entry was present and valid; false if absent or expired
     */
    public synchronized boolean consume(String key) {
        PendingEntry entry = store.get(key);
        if (entry == null) return false;
        if (isExpired(entry, System.currentTimeMillis())) {
            removeInternal(key);
            return false;
        }
        return removeInternal(key);
    }

    /**
     * Returns the timestamp for the given key if it exists and has not expired.
     * Returns null if the entry is absent or expired (and removes it if expired).
     */
    public synchronized Long peek(String key) {
        PendingEntry entry = store.get(key);
        if (entry == null) return null;
        if (isExpired(entry, System.currentTimeMillis())) {
            removeInternal(key);
            return null;
        }
        return entry.timestampMs();
    }

    /** Directly removes the entry for the given key. */
    public synchronized void remove(String key) {
        removeInternal(key);
    }

    /** Removes all entries whose keys start with the given prefix. */
    public synchronized void removeByPrefix(String prefix) {
        String username = extractFirstSegment(prefix);
        if (username == null) {
            for (String key : Set.copyOf(store.keySet())) {
                if (key.startsWith(prefix)) {
                    removeInternal(key);
                }
            }
            return;
        }

        Set<String> candidates = snapshotKeysForUsername(username);
        for (String key : candidates) {
            if (key.startsWith(prefix)) {
                removeInternal(key);
            }
        }
    }

    /**
     * Marks an entry as shown.
     * Callers can use this to avoid surfacing the same prompt twice.
     * Does nothing if the entry is absent or already marked shown.
     */
    public synchronized void markShown(String key) {
        PendingEntry entry = store.get(key);
        if (entry == null || entry.shown()) return;
        store.put(key, entry.withShown(true));
    }

    /**
     * Atomically marks an entry as shown when it exists, is not expired, and has not
     * been shown yet.
     *
     * @return true when the key was valid and transitioned to shown; false otherwise
     */
    public synchronized boolean markShownIfPending(String key) {
        PendingEntry entry = store.get(key);
        if (entry == null) return false;
        if (isExpired(entry, System.currentTimeMillis())) {
            removeInternal(key);
            return false;
        }
        if (entry.shown()) return false;
        store.put(key, entry.withShown(true));
        return true;
    }

    /** Removes all entries that are expired. */
    public synchronized void purgeExpired() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> {
            if (!isExpired(e.getValue(), now)) return false;
            unindexKey(e.getKey());
            return true;
        });
    }

    /** Immutable view of a registry entry exposed outside this class. */
    public record PendingView(long timestampMs, boolean shown) {}

    /** Returns an immutable snapshot of all entries. */
    public synchronized Set<Map.Entry<String, PendingView>> entries() {
        Set<Map.Entry<String, PendingView>> snapshot = new HashSet<>();
        for (Map.Entry<String, PendingEntry> entry : store.entrySet()) {
            PendingEntry e = entry.getValue();
            snapshot.add(Map.entry(entry.getKey(), new PendingView(e.timestampMs(), e.shown())));
        }
        return Collections.unmodifiableSet(snapshot);
    }

    /** Returns an immutable snapshot of keys for the given username from the reverse index. */
    public synchronized Set<String> keysForUsername(String username) {
        if (username == null || username.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> keys = userIndex.get(username.trim());
        return keys == null ? Collections.emptySet() : Set.copyOf(keys);
    }

    /** Removes all entries regardless of TTL. */
    public synchronized void clear() {
        store.clear();
        userIndex.clear();
    }

    // ---- Private helpers ----

    private void evictExpiredAndOldest(long now) {
        String oldestKey = null;
        long oldestTs = Long.MAX_VALUE;
        List<String> expiredKeys = new ArrayList<>();

        for (Map.Entry<String, PendingEntry> entry : store.entrySet()) {
            long ts = entry.getValue().timestampMs();
            if (isExpired(entry.getValue(), now)) {
                expiredKeys.add(entry.getKey());
            } else if (ts < oldestTs) {
                oldestTs = ts;
                oldestKey = entry.getKey();
            }
        }

        expiredKeys.forEach(this::removeInternal);
        if (store.size() < maxSize) return;

        // All remaining entries are within TTL; remove the oldest one.
        if (oldestKey != null) {
            removeInternal(oldestKey);
        }
    }

    private boolean removeInternal(String key) {
        if (store.remove(key) == null) {
            return false;
        }
        unindexKey(key);
        return true;
    }

    private void indexKey(String key) {
        String username = extractFirstSegment(key);
        if (username == null) return;
        userIndex.computeIfAbsent(username, ignored -> new HashSet<>()).add(key);
    }

    private void unindexKey(String key) {
        String username = extractFirstSegment(key);
        if (username == null) return;
        Set<String> keys = userIndex.get(username);
        if (keys == null) return;
        keys.remove(key);
        if (keys.isEmpty()) {
            userIndex.remove(username);
        }
    }

    private Set<String> snapshotKeysForUsername(String username) {
        Set<String> keys = userIndex.get(username);
        if (keys == null || keys.isEmpty()) {
            return Collections.emptySet();
        }
        return Set.copyOf(keys);
    }

    private boolean isExpired(PendingEntry entry, long nowMs) {
        return nowMs - entry.timestampMs() > ttlMs;
    }

    private static String extractFirstSegment(String key) {
        if (key == null) return null;
        int idx = key.indexOf(':');
        if (idx <= 0) return null;
        return key.substring(0, idx);
    }

    private record PendingEntry(long timestampMs, boolean shown) {
        private PendingEntry withShown(boolean nextShown) {
            if (shown == nextShown) {
                return this;
            }
            return new PendingEntry(timestampMs, nextShown);
        }
    }
}
