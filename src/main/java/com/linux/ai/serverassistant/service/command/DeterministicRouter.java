package com.linux.ai.serverassistant.service.command;

import java.util.Optional;

/**
 * A deterministic pre-AI router.
 *
 * If it matches a request, it can either:
 * - return an assistant text directly (bypass AI)
 * - request the backend to execute a Linux command and return formatted output
 */
public interface DeterministicRouter {

    Optional<Route> route(Context ctx);

    record Context(String message, String conversationId, String username) {}

    interface Route {}

    record AssistantText(String text) implements Route {}

    /**
     * @param responsePrefix text prepended before the tool result; may be null/empty
     */
    record LinuxCommand(String command, boolean confirm, String responsePrefix) implements Route {}

    /**
     * Capability marker for routers that process slash commands.
     */
    interface SlashCommandAware {}

    /**
     * Capability marker for routers whose LinuxCommand execution should bypass command-audit logging.
     */
    interface NoAuditLinuxCommandRouter {}

    /**
     * Optional capability for routers that keep per-conversation state and can clear it on cancel.
     */
    interface ConversationStateCleaner {
        void clearConversationState(String conversationId, String username);
    }
}
