package com.linux.ai.serverassistant.config;

import com.linux.ai.serverassistant.service.ai.ToolStatusBus;
import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.file.FileOperationService;
import com.linux.ai.serverassistant.service.user.UserManagementService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService.ExecutionOptions;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

@Configuration
public class AiToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(AiToolsConfig.class);
    private static final Set<String> DEBUG_COMMAND_ALLOWLIST = Set.of(
            "ls", "pwd", "whoami", "id", "date", "uptime", "uname");
    private final UserContext userContext;
    private final ToolStatusBus toolStatusBus;

    @Autowired
    public AiToolsConfig(UserContext userContext, ToolStatusBus toolStatusBus) {
        this.userContext = userContext;
        this.toolStatusBus = toolStatusBus;
    }

    @Bean
    @Description("執行 Linux 終端機指令。參數 `confirm` 預設為 false。注意：對於高風險指令（如 rm, mv），嚴禁在未經用戶明確授權（透過 `[CMD:::指令:::]` 標籤確認）的情況下將 `confirm` 設為 true。")
    public Function<CommandRequest, String> executeLinuxCommand(CommandExecutionService service) {
        return request -> executeWithAuth(
                request,
                r -> r.command() != null && !r.command().isBlank(),
                "命令不能為空。",
                actor -> {
                    log.debug("executeLinuxCommand TOOL CALLED: cmd='{}', confirm={}",
                            maskCommandForDebug(request.command()), request.confirm());
                    toolStatusBus.emitToolCall(request.contextKey(), "cmd", request.command());
                    try {
                        return service.execute(
                                request.command(),
                                ExecutionOptions.builder()
                                        .confirm(Boolean.TRUE.equals(request.confirm()))
                                        .user(actor)
                                        .build());
                    } finally {
                        toolStatusBus.emitToolDone(request.contextKey());
                    }
                });
    }

    // --- File Tools ---
    @Bean
    @Description("列出指定目錄下的檔案與子目錄 (List files in a directory)")
    public Function<ListDirectoryRequest, String> listDirectory(FileOperationService fileOperationService) {
        return request -> executeWithAuth(
                request,
                r -> r.path() != null && !r.path().isBlank(),
                "path 不能為空。",
                actor -> {
                    toolStatusBus.emitToolCall(request.contextKey(), "ls", request.path());
                    try {
                        return fileOperationService.listDirectory(request.path(), actor);
                    } finally {
                        toolStatusBus.emitToolDone(request.contextKey());
                    }
                });
    }

    @Bean
    @Description("讀取指定檔案的內容 (Read file content)")
    public Function<ReadFileRequest, String> readFileContent(FileOperationService fileOperationService) {
        return request -> executeWithAuth(
                request,
                r -> r.path() != null && !r.path().isBlank(),
                "path 不能為空。",
                actor -> {
                    toolStatusBus.emitToolCall(request.contextKey(), "read", request.path());
                    try {
                        return fileOperationService.readFileContent(request.path(), actor);
                    } finally {
                        toolStatusBus.emitToolDone(request.contextKey());
                    }
                });
    }

    @Bean
    @Description("建立新目錄 (Create directory)")
    public Function<CreateDirectoryRequest, String> createDirectory(FileOperationService fileOperationService) {
        return request -> executeWithAuth(
                request,
                r -> r.path() != null && !r.path().isBlank(),
                "path 不能為空。",
                actor -> {
                    toolStatusBus.emitToolCall(request.contextKey(), "mkdir", request.path());
                    try {
                        return fileOperationService.createDirectory(request.path(), actor);
                    } finally {
                        toolStatusBus.emitToolDone(request.contextKey());
                    }
                });
    }

    @Bean
    @Description("寫入內容到檔案 (Write content to file)")
    public Function<WriteFileRequest, String> writeFileContent(FileOperationService fileOperationService) {
        return request -> {
            if (request == null || request.content() == null) {
                return CommandMarkers.toolSecurityViolation("content 不能為空。");
            }
            return executeWithAuth(
                    request,
                    r -> r.path() != null && !r.path().isBlank(),
                    "path 不能為空。",
                    actor -> {
                        toolStatusBus.emitToolCall(request.contextKey(), "write", request.path());
                        try {
                            return fileOperationService.writeFileContent(request.path(), request.content(), actor);
                        } finally {
                            toolStatusBus.emitToolDone(request.contextKey());
                        }
                    });
        };
    }

    // --- User & System Tools ---
    @Bean
    @Description("管理 Linux 系統使用者。支援動作：list (列出), add (新增), delete (刪除)。新增時可選填 password。")
    public Function<UserRequest, String> manageUsers(UserManagementService userManagementService,
                                                     AdminAuthorizationService adminAuthorizationService) {
        return request -> {
            char[] password = request == null ? null : request.password();
            try {
                return executeWithAuth(
                        request,
                        r -> r.action() != null && !r.action().isBlank(),
                        "manageUsers.action 不能為空。",
                        actor -> {
                            String action = request.action().trim().toLowerCase(Locale.ROOT);
                            if (action.equals("list") || action.equals("add") || action.equals("delete")) {
                                if (!adminAuthorizationService.isAdmin(actor)) {
                                    return CommandMarkers.toolSecurityViolation("權限不足：manageUsers list/add/delete 僅限管理員。");
                                }
                            }
                            toolStatusBus.emitToolCall(request.contextKey(), "users", action);
                            try {
                                return userManagementService.manageUsers(
                                        action,
                                        request.username(),
                                        password,
                                        Boolean.TRUE.equals(request.confirm()),
                                        actor);
                            } finally {
                                toolStatusBus.emitToolDone(request.contextKey());
                            }
                        });
            } finally {
                clearSensitivePassword(password);
            }
        };
    }

    private void clearSensitivePassword(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    @Bean
    @Description("管理 SSH Authorized Keys。支援動作：list (列出), add (新增)。新增時必須提供 publicKey。可選填 username 指定目標使用者（預設為當前使用者）。")
    public Function<SshKeyRequest, String> manageSshKeys(UserManagementService userManagementService,
                                                         AdminAuthorizationService adminAuthorizationService) {
        return request -> executeWithAuth(
                request,
                r -> r.action() != null && !r.action().isBlank(),
                "manageSshKeys.action 不能為空。",
                actor -> {
                    String action = request.action().trim().toLowerCase(Locale.ROOT);
                    boolean isAdmin = adminAuthorizationService.isAdmin(actor);
                    if (action.equals("add") && !isAdmin) {
                        return CommandMarkers.toolSecurityViolation("權限不足：manageSshKeys add 僅限管理員。");
                    }
                    if (action.equals("list")) {
                        String target = request.username() == null ? "" : request.username().trim();
                        if (!target.isBlank() && !target.equals(actor) && !isAdmin) {
                            return CommandMarkers.toolSecurityViolation("權限不足：非管理員只能查看自己的 SSH keys。");
                        }
                    }
                    toolStatusBus.emitToolCall(request.contextKey(), "ssh", action);
                    try {
                        return userManagementService.manageSshKeys(
                                action,
                                request.username(),
                                request.publicKey(),
                                Boolean.TRUE.equals(request.confirm()),
                                actor);
                    } finally {
                        toolStatusBus.emitToolDone(request.contextKey());
                    }
                });
    }

    // ========== Private Helpers ==========

    /**
     * Shared AI tool execution template:
     * 1) request shape validation
     * 2) actor resolution from contextKey
     * 3) execute with context-bound identity
     */
    private <T extends HasContextKey> String executeWithAuth(
            T request,
            Predicate<T> requestValidator,
            String invalidMessage,
            Function<String, String> action) {
        if (request == null || !requestValidator.test(request)) {
            return CommandMarkers.toolSecurityViolation(invalidMessage);
        }
        return withAuthenticatedActor(request.contextKey(), action);
    }

    /**
     * Resolves the authenticated actor from the tool context key, then executes
     * the given action under that actor's identity.
     */
    private String withAuthenticatedActor(String contextKey, Function<String, String> action) {
        String actor = resolveAuthenticatedUsername(contextKey);
        if (actor == null) {
            return CommandMarkers.toolSecurityViolation("無法識別目前操作使用者，請重新登入後再試。");
        }
        return userContext.withContextKey(contextKey, () -> action.apply(actor));
    }

    /**
     * Resolves the authenticated username for AI tool execution.
     *
     * Identity is bound to a server-generated tool context key passed by the model.
     * Missing or invalid keys are rejected.
     */
    private String resolveAuthenticatedUsername(String explicitContextKey) {
        if (explicitContextKey == null || explicitContextKey.isBlank()) {
            log.warn("resolveUsername: missing contextKey, rejecting tool call. Thread: {}", Thread.currentThread().getName());
            return null;
        }
        String explicitUser = userContext.resolveFromActiveSessions(explicitContextKey);
        if (explicitUser != null && !explicitUser.isBlank()) {
            log.debug("resolveUsername: resolved from explicit contextKey => '{}'", explicitUser);
            return explicitUser.trim();
        }
        log.warn("resolveUsername: invalid/expired contextKey, rejecting tool call. Thread: {}",
                Thread.currentThread().getName());
        return null;
    }

    static String maskCommandForDebug(String command) {
        if (command == null || command.isBlank()) {
            return "<empty>";
        }
        String trimmed = command.trim();
        String executable = extractExecutable(trimmed);
        if (DEBUG_COMMAND_ALLOWLIST.contains(executable)) {
            return trimmed;
        }
        return "<redacted>";
    }

    private static String extractExecutable(String command) {
        String[] tokens = command.trim().split("\\s+");
        if (tokens.length == 0) {
            return "";
        }
        int index = 0;
        while (index < tokens.length && "sudo".equalsIgnoreCase(tokens[index])) {
            index++;
        }
        if (index >= tokens.length) {
            return "";
        }
        String executable = tokens[index];
        int slash = executable.lastIndexOf('/');
        if (slash >= 0 && slash < executable.length() - 1) {
            executable = executable.substring(slash + 1);
        }
        return executable.toLowerCase(Locale.ROOT);
    }

    // ========== Request Records ==========

    private interface HasContextKey {
        String contextKey();
    }

    public record CommandRequest(String command, Boolean confirm, String contextKey) implements HasContextKey {}

    public record ListDirectoryRequest(String path, String contextKey) implements HasContextKey {}

    public record ReadFileRequest(String path, String contextKey) implements HasContextKey {}

    public record CreateDirectoryRequest(String path, String username, String contextKey) implements HasContextKey {}

    public record WriteFileRequest(String path, String content, String username, String contextKey) implements HasContextKey {}

    public record UserRequest(String action, String username, char[] password, Boolean confirm, String contextKey) implements HasContextKey {}

    public record SshKeyRequest(String action, String username, String publicKey, Boolean confirm, String contextKey) implements HasContextKey {}
}
