package com.linux.ai.serverassistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for selecting UserContext storage backend and TTL.
 */
@Component
@ConfigurationProperties(prefix = "app.user-context")
public class UserContextStoreProperties {
    /** Store backend. Supported values: memory, redis. */
    private String store = "memory";

    /** Soft TTL for inactive session entries (used by in-memory backend). */
    private long ttlMs = 5 * 60 * 1_000L;

    /** Hard TTL for long-inactive/orphaned session entries. */
    private long hardTtlMs = 60 * 60 * 1_000L;

    /** Cleanup task interval (used by in-memory backend). */
    private long cleanupIntervalMinutes = 5L;

    private Redis redis = new Redis();

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public long getTtlMs() {
        return ttlMs;
    }

    public void setTtlMs(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public long getHardTtlMs() {
        return hardTtlMs;
    }

    public void setHardTtlMs(long hardTtlMs) {
        this.hardTtlMs = hardTtlMs;
    }

    public long getCleanupIntervalMinutes() {
        return cleanupIntervalMinutes;
    }

    public void setCleanupIntervalMinutes(long cleanupIntervalMinutes) {
        this.cleanupIntervalMinutes = cleanupIntervalMinutes;
    }

    public Redis getRedis() {
        return redis;
    }

    public void setRedis(Redis redis) {
        this.redis = (redis != null) ? redis : new Redis();
    }

    public static class Redis {
        /** Redis key namespace prefix for user context keys. */
        private String namespace = "server-assistant:user-context";

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }
    }
}
