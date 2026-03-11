package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.security.CommandValidator;
import com.linux.ai.serverassistant.security.SecureCredentialStore;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandExecutionServiceTimeoutTest {

    private ExecutorService outputCaptureExecutor;
    private ProcessFactory processFactory;
    private Process process;
    private CommandExecutionService service;
    private UserContext userContext;

    @BeforeEach
    void setUp() throws Exception {
        outputCaptureExecutor = Executors.newSingleThreadExecutor();
        processFactory = mock(ProcessFactory.class);
        process = mock(Process.class);
        userContext = new UserContext();

        CommandValidator commandValidator = mock(CommandValidator.class);
        CommandLogRepository commandLogRepository = mock(CommandLogRepository.class);
        SecureCredentialStore credentialStore = mock(SecureCredentialStore.class);
        PendingConfirmationManager pendingConfirmationManager = new PendingConfirmationManager();
        CommandConfirmationService commandConfirmationService =
                new CommandConfirmationService(pendingConfirmationManager, userContext);
        SudoCredentialInjector sudoCredentialInjector = new SudoCredentialInjector(userContext, credentialStore);

        service = CommandExecutionService.builder(
                        commandValidator,
                        commandLogRepository,
                        userContext,
                        sudoCredentialInjector,
                        commandConfirmationService)
                .processFactory(processFactory)
                .outputCaptureExecutor(outputCaptureExecutor)
                .manageOutputCaptureExecutorLifecycle(false)
                .build();

        when(processFactory.startCommand(anyBoolean(), any(), any())).thenReturn(process);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(process.destroyForcibly()).thenReturn(process);
    }

    @AfterEach
    void tearDown() {
        outputCaptureExecutor.shutdownNow();
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
    }

    @Test
    void runCommandWithResult_processTimeout_shouldForceDestroy() throws Exception {
        when(process.waitFor(3L, TimeUnit.SECONDS)).thenReturn(false);
        when(process.waitFor(2L, TimeUnit.SECONDS)).thenReturn(false);
        when(process.waitFor(1L, TimeUnit.SECONDS)).thenReturn(true);

        CommandExecutionService.ExecutionResult result = service.runCommandWithResult(
                "sleep 600",
                true,
                "root",
                256,
                false,
                3L);

        assertFalse(result.success());
        assertTrue(result.rawToolResult().contains("命令執行逾時"));
        verify(process).waitFor(3L, TimeUnit.SECONDS);
        verify(process).destroy();
        verify(process).destroyForcibly();
        verify(process).waitFor(1L, TimeUnit.SECONDS);
    }

    @Test
    void runCommandWithResult_processCompletes_shouldNotDestroyForcibly() throws Exception {
        when(process.waitFor(5L, TimeUnit.SECONDS)).thenReturn(true);
        when(process.exitValue()).thenReturn(0);

        CommandExecutionService.ExecutionResult result = service.runCommandWithResult(
                "echo ok",
                true,
                "root",
                256,
                false,
                5L);

        assertTrue(result.success());
        verify(process).waitFor(5L, TimeUnit.SECONDS);
        verify(process, never()).destroy();
        verify(process, never()).destroyForcibly();
    }
}
