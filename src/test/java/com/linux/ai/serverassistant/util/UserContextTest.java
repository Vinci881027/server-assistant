package com.linux.ai.serverassistant.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserContextTest {
    private final UserContext userContext = new UserContext();

    @AfterEach
    void tearDown() {
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
        RequestContextHolder.resetRequestAttributes();
    }

    // ========== Tool session tests ==========

    @Test
    void resolveToolSession_withoutCurrentContextKey_shouldReturnNull() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");

        assertNull(userContext.resolveFromActiveSessions());
        assertNull(userContext.resolveSessionIdFromActiveSessions());
    }

    @Test
    void resolveToolSession_sameUserDifferentKeys_withoutContextKey_shouldReturnNull() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");
        userContext.registerToolSession("tool-2", "alice", "sid-2");

        assertNull(userContext.resolveFromActiveSessions());
        assertNull(userContext.resolveSessionIdFromActiveSessions());
    }

    @Test
    void resolveToolSession_multipleUsers_withoutContextKey_shouldReturnNull() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");
        userContext.registerToolSession("tool-2", "bob", "sid-2");

        assertNull(userContext.resolveFromActiveSessions());
        assertNull(userContext.resolveSessionIdFromActiveSessions());
    }

    @Test
    void resolveToolSession_withCurrentContextKey_shouldReturnExactEntry() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");
        userContext.registerToolSession("tool-2", "bob", "sid-2");
        userContext.setCurrentContextKey("tool-2");

        assertEquals("bob", userContext.resolveFromActiveSessions());
        assertEquals("sid-2", userContext.resolveSessionIdFromActiveSessions());
    }

    @Test
    void resolveToolSession_byExplicitKey_shouldReturnExactSession() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");
        userContext.registerToolSession("tool-2", "bob", "sid-2");

        assertEquals("bob", userContext.resolveFromActiveSessions("tool-2"));
        assertEquals("sid-2", userContext.resolveSessionIdFromActiveSessions("tool-2"));
    }

    @Test
    void resolveToolSession_unknownKey_shouldReturnNull() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");

        assertNull(userContext.resolveFromActiveSessions("unknown-key"));
        assertNull(userContext.resolveSessionIdFromActiveSessions("unknown-key"));
    }

    @Test
    void releaseToolSession_shouldRemoveEntry() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");
        userContext.setCurrentContextKey("tool-1");

        userContext.releaseToolSession("tool-1");
        assertNull(userContext.resolveFromActiveSessions());
    }

    // ========== Conversation session tests ==========

    @Test
    void releaseConversationSession_concurrentRequests_shouldUseReferenceCount() {
        userContext.registerConversationSession("conv-1", "alice", "sid-1");
        userContext.registerConversationSession("conv-1", "alice", "sid-1");

        userContext.releaseConversationSession("conv-1");
        assertEquals("alice", userContext.resolveUsernameFromConversationSession("conv-1"));

        userContext.releaseConversationSession("conv-1");
        assertNull(userContext.resolveUsernameFromConversationSession("conv-1"));
    }

    @Test
    void registerConversationSession_sameIdDifferentUser_shouldThrow() {
        userContext.registerConversationSession("conv-1", "alice", "sid-1");

        assertThrows(IllegalStateException.class,
                () -> userContext.registerConversationSession("conv-1", "bob", "sid-2"));
    }

    // ========== resolveIdentity fallback chain tests ==========

    @Test
    void resolveIdentity_shouldUseToolSessionViaCurrentContextKey() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");
        userContext.setCurrentContextKey("tool-1");

        UserContext.ResolvedIdentity identity = userContext.resolveIdentity(" ", "conv-4");

        assertEquals("alice", identity.username());
        assertEquals("sid-1", identity.sessionId());
    }

    @Test
    void resolveIdentity_shouldFallbackToConversationSessionWhenCurrentKeyMissing() {
        userContext.registerConversationSession("conv-4", "dave", "sid-4");

        UserContext.ResolvedIdentity identity = userContext.resolveIdentity(null, "conv-4");

        assertEquals("dave", identity.username());
        assertEquals("sid-4", identity.sessionId());
    }

    @Test
    void resolveUsernameOrAnonymous_shouldUseToolSessionWhenAvailable() {
        userContext.registerToolSession("tool-1", "alice", "sid-1");
        userContext.setCurrentContextKey("tool-1");

        assertEquals("alice", userContext.resolveUsernameOrAnonymous());
    }

    @Test
    void withContextKey_shouldRestorePreviousContextKey() {
        userContext.setCurrentContextKey("tool-1");

        String inner = userContext.withContextKey("tool-2", userContext::getCurrentContextKey);

        assertEquals("tool-2", inner);
        assertEquals("tool-1", userContext.getCurrentContextKey());
    }

    @Test
    void resolveUsernameOrAnonymousPreferExplicit_shouldOverrideHttpSessionUser() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", "session-user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertEquals("session-user", userContext.resolveUsernameOrAnonymous("override-user", null));
        assertEquals("override-user", userContext.resolveUsernameOrAnonymousPreferExplicit("override-user", null));
    }

    // ========== TTL / expiry tests (tool sessions) ==========

    @Test
    void resolveToolSession_withinHardTtl_shouldRemainAvailable() throws Exception {
        UserContext shortTtlContext = new UserContext(new InMemoryUserContextStore(5L, 300L, 1L));
        shortTtlContext.registerToolSession("tool-live", "alice", "sid-1");

        Thread.sleep(40L);

        assertEquals("alice", shortTtlContext.resolveFromActiveSessions("tool-live"));
        assertEquals("sid-1", shortTtlContext.resolveSessionIdFromActiveSessions("tool-live"));
        shortTtlContext.shutdownCleanupScheduler();
    }

    @Test
    void resolveToolSession_afterHardTtl_shouldExpire() throws Exception {
        UserContext shortTtlContext = new UserContext(new InMemoryUserContextStore(5L, 20L, 1L));
        shortTtlContext.registerToolSession("tool-stale", "alice", "sid-1");

        Thread.sleep(40L);

        assertNull(shortTtlContext.resolveFromActiveSessions("tool-stale"));
        assertNull(shortTtlContext.resolveSessionIdFromActiveSessions("tool-stale"));
        shortTtlContext.shutdownCleanupScheduler();
    }
}
