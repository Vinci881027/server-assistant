package com.linux.ai.serverassistant.service.command;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Deterministic router for raw shell commands prefixed with '!'.
 *
 * Example:
 * - !docker ps
 * - !du -h /var/log
 *
 * This bypasses the AI model and executes through the existing secure
 * CommandExecutionService path.
 */
@Service
@Order(5)
public class ExclamationCommandRouter implements DeterministicRouter {

    @Override
    public Optional<Route> route(Context ctx) {
        if (ctx == null || ctx.message() == null) return Optional.empty();

        String raw = ctx.message().trim();
        if (!raw.startsWith("!")) return Optional.empty();

        String command = raw.substring(1).trim();
        if (command.isEmpty()) {
            return Optional.of(new AssistantText("請在 `!` 後輸入要執行的 Linux 指令，例如：`!docker ps`"));
        }

        return Optional.of(new LinuxCommand(command, false, null));
    }
}
