package com.linux.ai.serverassistant.service.command;

/**
 * Marks payloads that hold sensitive material so the manager can wipe them
 * before dropping references.
 */
public interface SensitivePendingPayload {

    void clearSensitiveData();
}
