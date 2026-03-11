package com.linux.ai.serverassistant.service.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.command.PendingConfirmationManager;
import com.linux.ai.serverassistant.service.file.FileOperationService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static com.linux.ai.serverassistant.util.ToolResultUtils.formatExecutionResult;

class UserManagementServiceTest {

    private CommandExecutionService commandExecutionService;
    private AdminAuthorizationService adminAuthorizationService;
    private FileOperationService fileOperationService;
    private UserManagementService service;

    @BeforeEach
    void setUp() {
        commandExecutionService = mock(CommandExecutionService.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);
        fileOperationService = mock(FileOperationService.class);

        service = new UserManagementService(
                commandExecutionService,
                mock(CommandLogRepository.class),
                adminAuthorizationService,
                new PendingConfirmationManager(),
                fileOperationService);
    }

    @Test
    void manageUsers_missingAction_shouldReturnError() {
        String result = service.manageUsers(null, "alice", null, false, "root");

        assertTrue(result.contains("未指定動作"));
        verify(commandExecutionService, never()).execute(any(), any());
    }

    @Test
    void manageUsers_list_nonAdmin_shouldReturnSecurityViolation() {
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);

        String result = service.manageUsers("list", null, null, false, "alice");

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        assertTrue(result != null);
        verify(commandExecutionService, never()).execute(any(), any());
    }

    @Test
    void manageUsers_list_admin_shouldDelegateToCommandExecutionService() {
        when(adminAuthorizationService.isAdmin("rootadmin")).thenReturn(true);
        when(commandExecutionService.execute(eq(UserCommandConstants.LIST_LOGIN_USERS_COMMAND), any()))
                .thenReturn("Tool Result:\nuser1");

        String result = service.manageUsers("list", null, null, false, "rootadmin");

        assertTrue(result.contains("Tool Result"));
        verify(commandExecutionService).execute(eq(UserCommandConstants.LIST_LOGIN_USERS_COMMAND), any());
    }

    @Test
    void manageSshKeys_add_nonAdmin_shouldReturnSecurityViolation() {
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);

        String result = service.manageSshKeys(
                "add",
                "bob",
                "ssh-ed25519 AAAATEST",
                true,
                "alice");

        assertTrue(result.contains("權限不足"));
    }

    @Test
    void manageSshKeys_add_keyCommentWithInvalidChars_shouldReturnInvalidFormat() {
        when(adminAuthorizationService.isAdmin("rootadmin")).thenReturn(true);

        String result = service.manageSshKeys(
                "add",
                "bob",
                "ssh-ed25519 AAAATEST comment-with-bang!",
                true,
                "rootadmin");

        assertTrue(result.contains("無效的 SSH 公鑰格式"));
        verify(commandExecutionService, never()).execute(any(), any());
    }

    @Test
    void manageSshKeys_add_keyCommentWithAllowedChars_shouldPassRegexCheck() {
        when(adminAuthorizationService.isAdmin("rootadmin")).thenReturn(true);

        String result = service.manageSshKeys(
                "add",
                "bob",
                "ssh-ed25519 AAAATEST user.name@host-01",
                true,
                "rootadmin");

        assertTrue(result.contains("使用者 bob 不存在"));
        verify(commandExecutionService).execute(any(), any());
    }

    @Test
    void appendSshKeyViaStdin_symlinkPath_shouldReturnSecurityViolation(@TempDir Path tempDir) throws Exception {
        Path realFile = tempDir.resolve("real_authorized_keys");
        Path linkFile = tempDir.resolve("authorized_keys_link");
        Files.writeString(realFile, "ssh-ed25519 EXISTING\n");
        Files.createSymbolicLink(linkFile, realFile);

        Method method = UserManagementService.class.getDeclaredMethod(
                "appendSshKeyViaStdin", String.class, String.class, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(service, linkFile.toString(), "ssh-ed25519 NEWKEY", "root");

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        verify(fileOperationService, never()).writeFileContent(any(), any(), any());
    }

    @Test
    void appendSshKeyViaStdin_regularFile_shouldMergeAndWrite(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("authorized_keys");
        Files.writeString(file, "ssh-ed25519 EXISTING");
        when(fileOperationService.writeFileContent(
                eq(file.toString()),
                eq("ssh-ed25519 EXISTING\nssh-ed25519 NEWKEY\n"),
                eq("root")))
                .thenReturn(formatExecutionResult("成功寫入檔案: " + file));

        Method method = UserManagementService.class.getDeclaredMethod(
                "appendSshKeyViaStdin", String.class, String.class, String.class);
        method.setAccessible(true);
        String result = (String) method.invoke(service, file.toString(), "ssh-ed25519 NEWKEY", "root");

        assertNull(result);
        verify(fileOperationService).writeFileContent(
                eq(file.toString()),
                eq("ssh-ed25519 EXISTING\nssh-ed25519 NEWKEY\n"),
                eq("root"));
    }

    @Test
    void sshKeyFileLocks_shouldUseCaffeineExpireAfterAccessTtl() throws Exception {
        Field field = UserManagementService.class.getDeclaredField("sshKeyFileLocks");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Cache<String, Object> cache = (Cache<String, Object>) field.get(service);

        Duration expiresAfter = cache.policy()
                .expireAfterAccess()
                .orElseThrow()
                .getExpiresAfter();
        assertEquals(Duration.ofMinutes(30), expiresAfter);
    }

    @Test
    void rollbackCreatedUser_failedWithoutOutput_shouldReturnExplicitError() throws Exception {
        when(commandExecutionService.executeWithResult(eq("userdel -r bob"), any()))
                .thenReturn(new CommandExecutionService.ExecutionResult(formatExecutionResult(""), false, 6));

        Method method = UserManagementService.class.getDeclaredMethod("rollbackCreatedUser", String.class, String.class);
        method.setAccessible(true);
        String rollbackError = (String) method.invoke(service, "bob", "root");

        assertTrue(rollbackError != null);
        assertTrue(rollbackError.contains("rollback 失敗"));
        assertTrue(rollbackError.contains("exit code: 6"));
    }

    @Test
    void rollbackCreatedUser_mailSpoolWarning_shouldTreatAsRollbackSuccess() throws Exception {
        when(commandExecutionService.executeWithResult(eq("userdel -r bob"), any()))
                .thenReturn(new CommandExecutionService.ExecutionResult(
                        formatExecutionResult("userdel: bob mail spool (/var/mail/bob) not found"),
                        false,
                        12));

        Method method = UserManagementService.class.getDeclaredMethod("rollbackCreatedUser", String.class, String.class);
        method.setAccessible(true);
        String rollbackError = (String) method.invoke(service, "bob", "root");

        assertNull(rollbackError);
    }

    @Test
    void destroyForciblyAndReapProcess_waitForSuccess_shouldNotCallOnExit() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(1L, TimeUnit.SECONDS)).thenReturn(true);

        Method method = UserManagementService.class.getDeclaredMethod(
                "destroyForciblyAndReapProcess", Process.class, long.class);
        method.setAccessible(true);
        method.invoke(service, process, 1L);

        verify(process).destroyForcibly();
        verify(process).waitFor(1L, TimeUnit.SECONDS);
        verify(process, never()).onExit();
    }

    @Test
    void destroyForciblyAndReapProcess_waitForTimeout_shouldRegisterOnExit() throws Exception {
        Process process = mock(Process.class);
        when(process.waitFor(1L, TimeUnit.SECONDS)).thenReturn(false);
        when(process.onExit()).thenReturn(CompletableFuture.completedFuture(process));

        Method method = UserManagementService.class.getDeclaredMethod(
                "destroyForciblyAndReapProcess", Process.class, long.class);
        method.setAccessible(true);
        method.invoke(service, process, 1L);

        verify(process).destroyForcibly();
        verify(process).waitFor(1L, TimeUnit.SECONDS);
        verify(process).onExit();
    }
}
