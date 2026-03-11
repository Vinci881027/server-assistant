package com.linux.ai.serverassistant.util;

import java.util.Map;

/**
 * Storage backend for UserContext session bindings.
 */
public interface UserContextStore {

    default void start() {
        // Default no-op.
    }

    default void stop() {
        // Default no-op.
    }

    void registerToolSession(String toolKey, String username, String sessionId);

    void releaseToolSession(String toolKey);

    String resolveUsernameFromToolSession(String toolKey);

    String resolveSessionIdFromToolSession(String toolKey);

    void registerConversationSession(String conversationId, String username, String sessionId);

    void releaseConversationSession(String conversationId);

    String resolveUsernameFromConversationSession(String conversationId);

    String resolveSessionIdFromConversationSession(String conversationId);

    void clearAllActiveSessions();

    int activeSessionCount();

    UserContext.ActiveSession peekActiveSession(String toolKey);

    Map<String, UserContext.ActiveSession> activeSessionsSnapshot();
}
