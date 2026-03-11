package com.linux.ai.serverassistant.service.command;

/**
 * Known confirmation scopes shared across command services.
 */
public enum PendingConfirmationScope {
    CMD("cmd", 2_000),
    USER_ADD("user_add", 500),
    OFFLOAD("offload", 2_000),
    MOUNT("mount", 2_000);

    private final String key;
    private final int maxSize;

    PendingConfirmationScope(String key, int maxSize) {
        this.key = key;
        this.maxSize = maxSize;
    }

    public String key() {
        return key;
    }

    public int maxSize() {
        return maxSize;
    }
}
