package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Manages pending high-risk command confirmations.
 *
 * Provides the public confirmation-state API (store, consume, clear, query)
 * previously embedded in {@link CommandExecutionService}. Callers that need
 * to inspect or manipulate confirmation state should inject this service
 * instead of going through {@code CommandExecutionService}.
 */
@Service
public class CommandConfirmationService {

    private final PendingConfirmationManager pendingConfirmationManager;
    private final UserContext userContext;

    @Autowired
    public CommandConfirmationService(
            PendingConfirmationManager pendingConfirmationManager,
            UserContext userContext) {
        this.pendingConfirmationManager = pendingConfirmationManager;
        this.userContext = userContext;
    }

    /**
     * Stores a raw-key pending confirmation entry (format: {@code username:scope:command}).
     * Used by routers that build the full key on the servlet thread to avoid
     * identity-loss on Netty tool threads.
     */
    public void storePendingConfirmation(String confirmationKey) {
        pendingConfirmationManager.storeRawKey(confirmationKey);
    }

    /**
     * Stores a CMD-scope pending confirmation for the given user + command.
     */
    public void storePendingCommand(String username, String command) {
        pendingConfirmationManager.store(PendingConfirmationScope.CMD, username, command);
    }

    /**
     * Consumes one CMD-scope pending confirmation for the given user + command.
     * Returns true only when a non-expired entry exists and is removed.
     */
    public boolean consumePendingConfirmation(String command, String username) {
        return pendingConfirmationManager.consume(PendingConfirmationScope.CMD, username, command);
    }

    /**
     * Checks whether a non-expired CMD-scope pending confirmation exists.
     * Does not consume the pending record.
     */
    public boolean hasPendingConfirmation(String command, String username) {
        return pendingConfirmationManager.has(PendingConfirmationScope.CMD, username, command);
    }

    /**
     * Clears one pending confirmation entry across all scopes for the given user + command.
     * Also clears anonymous fallback keys for compatibility.
     */
    public void clearPendingConfirmation(String command, String username) {
        pendingConfirmationManager.clearAllScopesForCommandWithAnonymousFallback(username, command);
    }

    /**
     * Clears one pending confirmation entry for the given scope + user + command.
     */
    public void clearScopedPendingConfirmation(PendingConfirmationScope scope, String username, String command) {
        pendingConfirmationManager.clear(scope, username, command);
    }

    /**
     * Clears CMD and USER_ADD scope pending confirmations for the current session user.
     */
    public void clearUserPendingConfirmations() {
        clearUserPendingConfirmations(userContext.resolveUsernameOrAnonymous());
    }

    /**
     * Clears CMD and USER_ADD scope pending confirmations for the specified user.
     */
    public void clearUserPendingConfirmations(String username) {
        pendingConfirmationManager.clearScopesForUser(
                username,
                PendingConfirmationScope.CMD,
                PendingConfirmationScope.USER_ADD);
    }

    /**
     * Clears pending confirmations for the given user in the specified scopes.
     */
    public void clearUserScopedPendingConfirmations(String username, PendingConfirmationScope... scopes) {
        pendingConfirmationManager.clearScopesForUser(username, scopes);
    }

    /**
     * Clears all pending confirmations across all scopes for the given user.
     */
    public void clearAllPendingConfirmations(String username) {
        pendingConfirmationManager.clearAllScopesForUser(username);
    }

    /**
     * Clears ALL pending confirmations across all scopes regardless of username.
     */
    public void clearAllPendingConfirmations() {
        pendingConfirmationManager.clearAll();
    }

    /**
     * Returns the [CMD:::...] confirmation prompt for any pending high-risk command.
     * Used as a fallback when the AI produces an empty response after requesting confirmation.
     */
    public Optional<String> getPendingCommandPrompt(String username) {
        return pendingConfirmationManager.getPendingCommandPrompt(username);
    }

    /**
     * Re-stores a previously consumed pending confirmation so the user can retry.
     * Called when a background job fails to start after the confirmation was consumed.
     */
    public void restorePendingConfirmation(String command, String username) {
        pendingConfirmationManager.store(PendingConfirmationScope.CMD, username, command);
    }
}
