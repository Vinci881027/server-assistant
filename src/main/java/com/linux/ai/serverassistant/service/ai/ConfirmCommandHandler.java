package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.service.JpaChatMemory;
import com.linux.ai.serverassistant.service.command.CommandConfirmationService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.command.CommandJobService;
import com.linux.ai.serverassistant.service.command.MountConfirmPayload;
import com.linux.ai.serverassistant.service.command.OffloadJobService;
import com.linux.ai.serverassistant.service.command.OffloadConfirmPayload;
import com.linux.ai.serverassistant.service.command.SlashCommandRiskyOperationService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.system.DiskMountService;
import com.linux.ai.serverassistant.service.user.UserManagementService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy-based handler for confirmed command execution.
 *
 * Keeps ChatService focused on streaming orchestration and delegates
 * command-specific confirmation branches to dedicated handlers.
 */
@Service
final class ConfirmCommandHandler {

    private static final Pattern USER_DELETE_CONFIRM_PATTERN =
            Pattern.compile("^\\s*userdel\\s+-r\\s+([a-z_][a-z0-9_-]*)\\s*$");
    private static final Pattern USER_ADD_CONFIRM_PATTERN =
            Pattern.compile("^\\s*useradd\\s+-m\\s+-s\\s+/bin/bash\\s+([a-z_][a-z0-9_-]*)\\s*$");
    private static final Set<String> BACKGROUND_DOCKER_ACTIONS = Set.of("pull", "build", "save", "load");
    private static final Set<String> BACKGROUND_APT_ACTIONS = Set.of(
            "install", "remove", "update", "upgrade", "dist-upgrade", "full-upgrade", "autoremove");
    private static final Set<String> BACKGROUND_YUM_DNF_ACTIONS = Set.of(
            "install", "remove", "update", "upgrade", "distro-sync");

    private final UserManagementService userManagementService;
    private final CommandExecutionService commandExecutionService;
    private final CommandConfirmationService commandConfirmationService;
    private final SlashCommandRiskyOperationService riskyOperationService;
    private final DiskMountService diskMountService;
    private final AdminAuthorizationService adminAuthorizationService;
    private final OffloadJobService offloadJobService;
    private final CommandJobService commandJobService;
    private final JpaChatMemory jpaChatMemory;
    private final List<ConfirmHandler> handlers;

    ConfirmCommandHandler(UserManagementService userManagementService,
                          CommandExecutionService commandExecutionService,
                          CommandConfirmationService commandConfirmationService,
                          SlashCommandRiskyOperationService riskyOperationService,
                          DiskMountService diskMountService,
                          AdminAuthorizationService adminAuthorizationService,
                          OffloadJobService offloadJobService,
                          CommandJobService commandJobService,
                          JpaChatMemory jpaChatMemory) {
        this.userManagementService = userManagementService;
        this.commandExecutionService = commandExecutionService;
        this.commandConfirmationService = commandConfirmationService;
        this.riskyOperationService = riskyOperationService;
        this.diskMountService = diskMountService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.offloadJobService = offloadJobService;
        this.commandJobService = commandJobService;
        this.jpaChatMemory = jpaChatMemory;
        this.handlers = List.of(
                new OffloadConfirmHandler(),
                new MountConfirmHandler(),
                new UserAddConfirmHandler(),
                new UserDeleteConfirmHandler(),
                new BackgroundCommandHandler(),
                new DefaultConfirmedCommandHandler()
        );
    }

    HandlerResult handle(String command, String username, String conversationId, String sessionId) {
        ConfirmContext context = new ConfirmContext(
                command,
                username,
                conversationId,
                sessionId,
                SlashCommandRiskyOperationService.parseOffloadConfirmCommand(command).orElse(null),
                SlashCommandRiskyOperationService.parseMountConfirmCommand(command).orElse(null),
                extractUserTarget(USER_ADD_CONFIRM_PATTERN, command),
                extractUserTarget(USER_DELETE_CONFIRM_PATTERN, command),
                shouldRunAsBackgroundCommand(command)
        );

        for (ConfirmHandler handler : handlers) {
            if (handler.accepts(context)) {
                return handler.execute(context);
            }
        }
        var execution = commandExecutionService.executeConfirmedCommandWithResult(command, username);
        if (execution == null) {
            return HandlerResult.failure(
                    command,
                    CommandMarkers.toolSecurityViolation("命令執行狀態遺失，請重新操作後再試。"),
                    null);
        }
        return HandlerResult.withExecution(
                command, execution.rawToolResult(), execution.success(), execution.exitCode());
    }

    private interface ConfirmHandler {
        boolean accepts(ConfirmContext context);
        HandlerResult execute(ConfirmContext context);
    }

    record HandlerResult(
            String commandForMemory,
            String rawToolResult,
            String immediateResponse,
            boolean executionFailed,
            Integer exitCode) {
        static HandlerResult failure(String commandForMemory, String rawToolResult, Integer exitCode) {
            return new HandlerResult(commandForMemory, rawToolResult, null, true, exitCode);
        }

        static HandlerResult withExecution(
                String commandForMemory,
                String rawToolResult,
                boolean success,
                Integer exitCode) {
            return new HandlerResult(commandForMemory, rawToolResult, null, !success, exitCode);
        }

        static HandlerResult immediate(String commandForMemory, String immediateResponse) {
            return new HandlerResult(commandForMemory, null, immediateResponse, false, null);
        }
    }

    private record ConfirmContext(
            String command,
            String username,
            String conversationId,
            String sessionId,
            @Nullable OffloadConfirmPayload offloadConfirm,
            @Nullable MountConfirmPayload mountConfirm,
            String addTargetUser,
            String deleteTargetUser,
            boolean backgroundCommand) {
    }

    private final class OffloadConfirmHandler implements ConfirmHandler {
        @Override
        public boolean accepts(ConfirmContext context) {
            return context.offloadConfirm() != null;
        }

        @Override
        public HandlerResult execute(ConfirmContext context) {
            String adminViolation = requireAdmin(context.username(), "權限不足：`/offload` 僅限管理員操作。");
            if (adminViolation != null) {
                return HandlerResult.failure(context.command(), adminViolation, null);
            }
            if (!riskyOperationService.consumeOffloadPendingConfirmation(context.username(), context.command())) {
                return HandlerResult.failure(
                        context.command(),
                        CommandMarkers.toolSecurityViolation("找不到此 offload 指令的待確認紀錄，請重新操作。"),
                        null);
            }

            OffloadConfirmPayload payload =
                    Objects.requireNonNull(context.offloadConfirm(), "offloadConfirm must not be null");
            String raw = CommandMarkers.toolSecurityViolation("無法啟動 offload 任務。");
            String jobId = null;
            try {
                jobId = offloadJobService.startOffloadJob(
                        context.username(),
                        payload.source(),
                        payload.targetRoot(),
                        finalResult -> appendAsyncResult(
                                context.conversationId(),
                                context.username(),
                                finalResult,
                                "❌ Offload 中止：無法取得結果。")
                );
            } catch (IllegalArgumentException | IllegalStateException ex) {
                raw = CommandMarkers.toolSecurityViolation(
                        ex.getMessage() == null ? "無法啟動 offload 任務。" : ex.getMessage());
            }

            if (jobId == null || jobId.isBlank()) {
                return HandlerResult.failure(context.command(), raw, null);
            }

            String startedText = (
                    "⏳ Offload 任務已啟動（Job ID: `%s`），可於前端查看進度。%n%s"
            ).formatted(jobId, CommandMarkers.resolvedCmdMarker(context.command(), "confirmed"));
            replacePromptOrAppend(context.conversationId(), context.command(), startedText, context.username());
            return HandlerResult.immediate(context.command(), CommandMarkers.offloadJobResult(jobId));
        }
    }

    private final class MountConfirmHandler implements ConfirmHandler {
        @Override
        public boolean accepts(ConfirmContext context) {
            return context.mountConfirm() != null;
        }

        @Override
        public HandlerResult execute(ConfirmContext context) {
            String adminViolation = requireAdmin(context.username(), "權限不足：mount 僅限管理員操作。");
            if (adminViolation != null) {
                return HandlerResult.failure(context.command(), adminViolation, null);
            }
            if (!riskyOperationService.consumeMountPendingConfirmation(context.username(), context.command())) {
                return HandlerResult.failure(
                        context.command(),
                        CommandMarkers.toolSecurityViolation("找不到此 mount 指令的待確認紀錄，請重新操作。"),
                        null);
            }

            MountConfirmPayload payload =
                    Objects.requireNonNull(context.mountConfirm(), "mountConfirm must not be null");
            var result = diskMountService.executeConfirmedMountWithResult(
                    payload.device(), payload.target(), payload.fstype(), payload.options());
            return HandlerResult.withExecution(
                    context.command(), result.rawToolResult(), result.success(), result.exitCode());
        }
    }

    private final class UserAddConfirmHandler implements ConfirmHandler {
        @Override
        public boolean accepts(ConfirmContext context) {
            return context.addTargetUser() != null;
        }

        @Override
        public HandlerResult execute(ConfirmContext context) {
            String adminViolation = requireAdmin(context.username(), "權限不足：manageUsers add/delete 僅限管理員。");
            if (adminViolation != null) {
                return HandlerResult.failure(context.command(), adminViolation, null);
            }
            var result = userManagementService.executeConfirmedAddUser(context.addTargetUser(), context.username());
            return HandlerResult.withExecution(
                    context.command(), result.rawToolResult(), result.success(), result.exitCode());
        }
    }

    private final class UserDeleteConfirmHandler implements ConfirmHandler {
        @Override
        public boolean accepts(ConfirmContext context) {
            return context.deleteTargetUser() != null;
        }

        @Override
        public HandlerResult execute(ConfirmContext context) {
            String adminViolation = requireAdmin(context.username(), "權限不足：manageUsers add/delete 僅限管理員。");
            if (adminViolation != null) {
                return HandlerResult.failure(context.command(), adminViolation, null);
            }
            var result = userManagementService.executeConfirmedDeleteUser(context.deleteTargetUser(), context.username());
            return HandlerResult.withExecution(
                    context.command(), result.rawToolResult(), result.success(), result.exitCode());
        }
    }

    private final class BackgroundCommandHandler implements ConfirmHandler {
        @Override
        public boolean accepts(ConfirmContext context) {
            return context.backgroundCommand();
        }

        @Override
        public HandlerResult execute(ConfirmContext context) {
            if (!commandConfirmationService.consumePendingConfirmation(context.command(), context.username())) {
                return HandlerResult.failure(
                        context.command(),
                        CommandMarkers.toolSecurityViolation("找不到此指令的待確認紀錄，請重新操作。"),
                        null);
            }

            String raw = CommandMarkers.toolSecurityViolation("無法啟動背景任務。");
            String jobId = null;
            try {
                jobId = commandJobService.startCommandJob(
                        context.username(),
                        context.sessionId(),
                        context.command(),
                        finalResult -> appendAsyncResult(
                                context.conversationId(),
                                context.username(),
                                finalResult,
                                "❌ 背景任務中止：無法取得結果。")
                );
            } catch (Exception ex) {
                // Job failed to start after confirmation was consumed — restore it so user can retry.
                commandConfirmationService.restorePendingConfirmation(context.command(), context.username());
                raw = CommandMarkers.toolSecurityViolation(
                        "無法啟動背景任務：" + (ex.getMessage() != null ? ex.getMessage() : "未知錯誤"));
            }

            if (jobId == null || jobId.isBlank()) {
                return HandlerResult.failure(context.command(), raw, null);
            }

            String startedText = (
                    "⏳ 背景任務已啟動（Job ID: `%s`），可於前端查看進度。%n%s"
            ).formatted(jobId, CommandMarkers.resolvedCmdMarker(context.command(), "confirmed"));
            replacePromptOrAppend(context.conversationId(), context.command(), startedText, context.username());
            return HandlerResult.immediate(context.command(), CommandMarkers.bgJobResult(jobId));
        }
    }

    private final class DefaultConfirmedCommandHandler implements ConfirmHandler {
        @Override
        public boolean accepts(ConfirmContext context) {
            return true;
        }

        @Override
        public HandlerResult execute(ConfirmContext context) {
            var execution = commandExecutionService.executeConfirmedCommandWithResult(
                    context.command(),
                    context.username());
            if (execution == null) {
                return HandlerResult.failure(
                        context.command(),
                        CommandMarkers.toolSecurityViolation("命令執行狀態遺失，請重新操作後再試。"),
                        null);
            }
            return HandlerResult.withExecution(
                    context.command(), execution.rawToolResult(), execution.success(), execution.exitCode());
        }
    }

    private String requireAdmin(String username, String violationMessage) {
        if (adminAuthorizationService.isAdmin(username)) {
            return null;
        }
        return CommandMarkers.toolSecurityViolation(violationMessage);
    }

    private void appendAsyncResult(String conversationId, String username, String finalResult, String fallbackText) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String content = (finalResult == null || finalResult.isBlank()) ? fallbackText : finalResult.trim();
        jpaChatMemory.add(conversationId, List.of(new AssistantMessage(content)), username);
    }

    private void replacePromptOrAppend(String conversationId, String command, String content, String username) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        boolean replaced = jpaChatMemory.replaceLatestAssistantCommandPrompt(conversationId, command, content);
        if (!replaced) {
            jpaChatMemory.add(conversationId, List.of(new AssistantMessage(content)), username);
        }
    }

    private static String extractUserTarget(Pattern pattern, String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        Matcher matcher = pattern.matcher(command);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private boolean shouldRunAsBackgroundCommand(String command) {
        if (command == null || command.isBlank()) return false;
        String normalized = command.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        if (normalized.startsWith("cp ")
                || normalized.startsWith("rsync ")
                || normalized.startsWith("tar ")
                || normalized.startsWith("zip ")
                || normalized.startsWith("unzip ")) {
            return true;
        }

        String[] parts = normalized.split("\\s+");
        if (parts.length < 2) return false;
        String cmd = parts[0];
        String action = parts[1];

        if ("docker".equals(cmd)) {
            return BACKGROUND_DOCKER_ACTIONS.contains(action);
        }
        if ("apt".equals(cmd)) {
            return BACKGROUND_APT_ACTIONS.contains(action);
        }
        if ("yum".equals(cmd) || "dnf".equals(cmd)) {
            return BACKGROUND_YUM_DNF_ACTIONS.contains(action);
        }
        return false;
    }
}
