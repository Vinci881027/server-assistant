package com.linux.ai.serverassistant.config;

import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.file.FileOperationService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.user.UserManagementService;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiToolsConfigTest {

    private UserContext userContext;
    private AiToolsConfig config;
    private CommandExecutionService commandExecutionService;
    private FileOperationService fileOperationService;
    private UserManagementService userManagementService;
    private AdminAuthorizationService adminAuthorizationService;

    @BeforeEach
    void setUp() {
        userContext = new UserContext();
        config = new AiToolsConfig(userContext);
        commandExecutionService = mock(CommandExecutionService.class);
        fileOperationService = mock(FileOperationService.class);
        userManagementService = mock(UserManagementService.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);
        clearUserResolutionContext();
    }

    @AfterEach
    void tearDown() {
        clearUserResolutionContext();
    }

    @Test
    void executeLinuxCommand_missingContextKey_shouldBeRejected() {
        Function<AiToolsConfig.CommandRequest, String> tool = config.executeLinuxCommand(commandExecutionService);
        String result = tool.apply(new AiToolsConfig.CommandRequest("whoami", false, null));

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        verify(commandExecutionService, never()).execute(anyString(), any());
    }

    @Test
    void executeLinuxCommand_validContextKey_shouldDelegateWithResolvedActor() {
        String contextKey = bindActor("alice");
        when(commandExecutionService.execute(eq("whoami"), any())).thenReturn("Tool Result:\nalice");

        Function<AiToolsConfig.CommandRequest, String> tool = config.executeLinuxCommand(commandExecutionService);
        String result = tool.apply(new AiToolsConfig.CommandRequest("whoami", false, contextKey));

        assertEquals("Tool Result:\nalice", result);
        verify(commandExecutionService).execute(
                eq("whoami"),
                argThat(options -> options != null && !options.confirm() && "alice".equals(options.username())));
    }

    @Test
    void manageUsersAdd_nonAdmin_shouldBeBlocked() {
        String contextKey = bindActor("alice");
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);

        Function<AiToolsConfig.UserRequest, String> tool = config.manageUsers(userManagementService, adminAuthorizationService);
        String result = tool.apply(new AiToolsConfig.UserRequest("add", "devops", "pass123".toCharArray(), true, contextKey));

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        assertTrue(result.contains("僅限管理員"));
        verify(userManagementService, never()).manageUsers(anyString(), any(), any(), anyBoolean(), anyString());
    }

    @Test
    void manageUsersAdd_admin_shouldDelegate() {
        String contextKey = bindActor("rootadmin");
        when(adminAuthorizationService.isAdmin("rootadmin")).thenReturn(true);
        when(userManagementService.manageUsers(eq("add"), eq("devops"), any(), eq(true), eq("rootadmin")))
                .thenReturn("Tool Result:\nOK");

        Function<AiToolsConfig.UserRequest, String> tool = config.manageUsers(userManagementService, adminAuthorizationService);
        String result = tool.apply(new AiToolsConfig.UserRequest("  ADD  ", "devops", "pass123".toCharArray(), true, contextKey));

        assertEquals("Tool Result:\nOK", result);
        verify(userManagementService).manageUsers(
                eq("add"),
                eq("devops"),
                argThat(password -> password != null && password.length == "pass123".length()),
                eq(true),
                eq("rootadmin"));
    }

    @Test
    void manageSshKeysList_nonAdminOtherUser_shouldBeBlocked() {
        String contextKey = bindActor("alice");
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);

        Function<AiToolsConfig.SshKeyRequest, String> tool = config.manageSshKeys(userManagementService, adminAuthorizationService);
        String result = tool.apply(new AiToolsConfig.SshKeyRequest("list", "bob", null, false, contextKey));

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        assertTrue(result.contains("非管理員只能查看自己的 SSH keys"));
        verify(userManagementService, never()).manageSshKeys(anyString(), any(), any(), anyBoolean(), anyString());
    }

    @Test
    void listDirectory_shouldDelegateWithActor() {
        String contextKey = bindActor("alice");
        when(fileOperationService.listDirectory("/etc", "alice")).thenReturn("Tool Result:\ndelegated");

        Function<AiToolsConfig.ListDirectoryRequest, String> tool = config.listDirectory(fileOperationService);
        String result = tool.apply(new AiToolsConfig.ListDirectoryRequest("/etc", contextKey));

        assertEquals("Tool Result:\ndelegated", result);
        verify(fileOperationService).listDirectory("/etc", "alice");
    }

    @Test
    void writeFileContent_invalidContextKey_shouldBeRejected() {
        Function<AiToolsConfig.WriteFileRequest, String> tool = config.writeFileContent(fileOperationService);
        String result = tool.apply(new AiToolsConfig.WriteFileRequest("/home/alice/a.txt", "x", null, "ctx-invalid"));

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        verify(fileOperationService, never()).writeFileContent(anyString(), anyString(), anyString());
    }

    @Test
    void maskCommandForDebug_allowlistedCommand_shouldKeepFullCommand() {
        String masked = AiToolsConfig.maskCommandForDebug(" sudo /usr/bin/whoami ");

        assertEquals("sudo /usr/bin/whoami", masked);
    }

    @Test
    void maskCommandForDebug_nonAllowlistedCommand_shouldRedactArguments() {
        String masked = AiToolsConfig.maskCommandForDebug("curl -H 'Authorization: Bearer token' https://api.example.com");

        assertEquals("<redacted>", masked);
    }

    private String bindActor(String username) {
        String contextKey = "ctx-" + username;
        userContext.registerToolSession(contextKey, username, "sid-" + username);
        return contextKey;
    }

    private void clearUserResolutionContext() {
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
    }
}
