package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CommandConfirmationServiceTest {

    private PendingConfirmationManager pendingConfirmationManager;
    private UserContext userContext;
    private CommandConfirmationService service;

    @BeforeEach
    void setUp() {
        pendingConfirmationManager = new PendingConfirmationManager();
        userContext = new UserContext();
        service = new CommandConfirmationService(pendingConfirmationManager, userContext);
    }

    @AfterEach
    void tearDown() {
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
    }

    @Test
    void storePendingConfirmation_shouldBeRetrievable() {
        service.clearAllPendingConfirmations();
        service.storePendingConfirmation("alice:cmd:rm /tmp/foo");

        var prompt = service.getPendingCommandPrompt("alice");
        assertTrue(prompt.isPresent());
        assertTrue(prompt.get().contains("rm /tmp/foo"));
    }

    @Test
    void getPendingCommandPrompt_marksAsShown() {
        service.clearAllPendingConfirmations();
        service.storePendingConfirmation("alice:cmd:rm /tmp/foo");

        // First call returns it
        assertTrue(service.getPendingCommandPrompt("alice").isPresent());
        // Second call should not return it again (marked as shown)
        assertTrue(service.getPendingCommandPrompt("alice").isEmpty());
    }

    @Test
    void clearAllPendingConfirmations_shouldClearEverything() {
        service.clearAllPendingConfirmations();
        service.storePendingConfirmation("alice:cmd:rm /tmp/a");
        service.storePendingConfirmation("bob:cmd:reboot");

        service.clearAllPendingConfirmations();

        assertTrue(service.getPendingCommandPrompt("alice").isEmpty());
        assertTrue(service.getPendingCommandPrompt("bob").isEmpty());
    }

    @Test
    void clearUserPendingConfirmations_shouldClearOnlyThatUser() {
        service.clearAllPendingConfirmations();
        service.storePendingConfirmation("alice:cmd:rm /tmp/a");
        service.storePendingConfirmation("bob:cmd:reboot");

        service.clearUserPendingConfirmations("alice");

        var prompt = service.getPendingCommandPrompt("bob");
        assertTrue(prompt.isPresent());
        assertTrue(prompt.get().contains("reboot"));
    }

    @Test
    void consumePendingConfirmation_shouldAllowOnlyOnce() {
        service.clearAllPendingConfirmations();
        service.storePendingConfirmation("alice:cmd:rm /tmp/once");

        assertTrue(service.consumePendingConfirmation("rm /tmp/once", "alice"));
        assertFalse(service.consumePendingConfirmation("rm /tmp/once", "alice"));
    }

    @Test
    void clearPendingConfirmation_shouldClearAcrossScopesForSameCommand() {
        service.clearAllPendingConfirmations();
        String command = "shared-confirm-command";
        pendingConfirmationManager.store(PendingConfirmationScope.CMD, "alice", command);
        pendingConfirmationManager.store(PendingConfirmationScope.USER_ADD, "alice", command);
        pendingConfirmationManager.store(PendingConfirmationScope.MOUNT, "alice", command);

        service.clearPendingConfirmation(command, "alice");

        assertFalse(pendingConfirmationManager.has(PendingConfirmationScope.CMD, "alice", command));
        assertFalse(pendingConfirmationManager.has(PendingConfirmationScope.USER_ADD, "alice", command));
        assertFalse(pendingConfirmationManager.has(PendingConfirmationScope.MOUNT, "alice", command));
    }

    @Test
    void clearUserPendingConfirmations_shouldClearCmdAndUserAddScopes() {
        service.clearAllPendingConfirmations();
        pendingConfirmationManager.store(PendingConfirmationScope.CMD, "alice", "cmd-action");
        pendingConfirmationManager.store(PendingConfirmationScope.USER_ADD, "alice", "user-add-action");
        pendingConfirmationManager.store(PendingConfirmationScope.MOUNT, "alice", "mount-action");

        service.clearUserPendingConfirmations("alice");

        assertFalse(pendingConfirmationManager.has(PendingConfirmationScope.CMD, "alice", "cmd-action"));
        assertFalse(pendingConfirmationManager.has(PendingConfirmationScope.USER_ADD, "alice", "user-add-action"));
        assertTrue(pendingConfirmationManager.has(PendingConfirmationScope.MOUNT, "alice", "mount-action"));
    }

    @Test
    void storePendingCommand_shouldBeConsumable() {
        service.clearAllPendingConfirmations();
        service.storePendingCommand("alice", "rm /tmp/bar");

        assertTrue(service.consumePendingConfirmation("rm /tmp/bar", "alice"));
        assertFalse(service.consumePendingConfirmation("rm /tmp/bar", "alice"));
    }

    @Test
    void restorePendingConfirmation_shouldAllowRetry() {
        service.clearAllPendingConfirmations();
        service.storePendingCommand("alice", "rm /tmp/retry");
        service.consumePendingConfirmation("rm /tmp/retry", "alice");

        // Restore so user can retry
        service.restorePendingConfirmation("rm /tmp/retry", "alice");

        assertTrue(service.consumePendingConfirmation("rm /tmp/retry", "alice"));
    }
}
