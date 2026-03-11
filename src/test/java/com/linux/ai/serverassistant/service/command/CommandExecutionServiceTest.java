package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.security.CommandValidator;
import com.linux.ai.serverassistant.security.SecureCredentialStore;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CommandExecutionServiceTest {

    private CommandValidator commandValidator;
    private CommandConfirmationService commandConfirmationService;
    private UserContext userContext;
    private StubCommandExecutionService service;

    @BeforeEach
    void setUp() {
        commandValidator = mock(CommandValidator.class);
        CommandLogRepository commandLogRepository = mock(CommandLogRepository.class);
        SecureCredentialStore credentialStore = mock(SecureCredentialStore.class);
        userContext = new UserContext();
        PendingConfirmationManager pendingConfirmationManager = new PendingConfirmationManager();
        commandConfirmationService = new CommandConfirmationService(pendingConfirmationManager, userContext);
        SudoCredentialInjector sudoCredentialInjector = new SudoCredentialInjector(userContext, credentialStore);

        service = new StubCommandExecutionService(
                commandValidator,
                commandLogRepository,
                userContext,
                sudoCredentialInjector,
                commandConfirmationService);
    }

    @AfterEach
    void tearDown() {
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
    }

    @Test
    void execute_blankCommand_shouldReturnSecurityViolation() {
        String result = service.execute(" ", CommandExecutionService.ExecutionOptions.of());

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        assertTrue(result.contains("命令不能為空"));
    }

    @Test
    void execute_highRiskWithoutConfirm_shouldReturnPendingConfirmationState() {
        when(commandValidator.validate("rm -rf /tmp/test"))
                .thenReturn(CommandValidator.ValidationResult.requiresConfirmation("rm"));

        CommandExecutionService.ExecutionResult result = service.executeWithResult(
                "rm -rf /tmp/test",
                CommandExecutionService.ExecutionOptions.builder().user("alice").build());

        assertFalse(result.success());
        assertTrue(result.isPendingConfirmation());
        assertTrue(result.rawToolResult().contains("[CMD:::rm -rf /tmp/test:::]"));
        assertTrue(commandConfirmationService.hasPendingConfirmation("rm -rf /tmp/test", "alice"));
    }

    @Test
    void execute_confirmedWithoutPending_shouldReturnSecurityViolation() {
        when(commandValidator.validate("rm -rf /tmp/test"))
                .thenReturn(CommandValidator.ValidationResult.valid());

        String result = service.execute(
                "rm -rf /tmp/test",
                CommandExecutionService.ExecutionOptions.builder().confirmed().user("alice").build());

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        assertTrue(result.contains("找不到此指令的待確認紀錄"));
    }

    @Test
    void execute_confirmedWithPending_shouldRunCommandAsUser() {
        when(commandValidator.validate("echo ok"))
                .thenReturn(CommandValidator.ValidationResult.valid());
        commandConfirmationService.storePendingCommand("alice", "echo ok");

        CommandExecutionService.ExecutionResult result = service.executeWithResult(
                "echo ok",
                CommandExecutionService.ExecutionOptions.builder().confirmed().user("alice").build());

        assertTrue(result.success());
        assertEquals("echo ok", service.lastCommand);
        assertFalse(service.lastForceRoot);
        assertEquals("alice", service.lastActorUsername);
    }

    @Test
    void execute_trustedRoot_shouldRunAsRootExecutionMode() {
        CommandExecutionService.ExecutionResult result = service.executeWithResult(
                "systemctl status ssh",
                CommandExecutionService.ExecutionOptions.builder().trustedRoot().user("root").build());

        assertTrue(result.success());
        assertEquals("systemctl status ssh", service.lastCommand);
        assertTrue(service.lastForceRoot);
        assertEquals("root", service.lastActorUsername);
    }

    private static final class StubCommandExecutionService extends CommandExecutionService {
        private String lastCommand;
        private boolean lastForceRoot;
        private String lastActorUsername;
        private int lastMaxOutputChars;
        private boolean lastAuditEnabled;
        private Long lastTimeoutOverride;

        StubCommandExecutionService(
                CommandValidator commandValidator,
                CommandLogRepository commandLogRepository,
                UserContext userContext,
                SudoCredentialInjector sudoCredentialInjector,
                CommandConfirmationService commandConfirmationService) {
            super(commandValidator, commandLogRepository, userContext, sudoCredentialInjector, commandConfirmationService);
        }

        @Override
        protected ExecutionResult runCommandWithResult(
                String command,
                boolean forceRootExecution,
                String actorUsername,
                int maxOutputChars,
                boolean auditEnabled,
                Long timeoutSecondsOverride) {
            this.lastCommand = command;
            this.lastForceRoot = forceRootExecution;
            this.lastActorUsername = actorUsername;
            this.lastMaxOutputChars = maxOutputChars;
            this.lastAuditEnabled = auditEnabled;
            this.lastTimeoutOverride = timeoutSecondsOverride;
            return new ExecutionResult("Tool Result:\nOK", true, 0);
        }
    }
}
