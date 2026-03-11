package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.security.CommandValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeleteRouterTest {

    private CommandValidator commandValidator;
    private CommandConfirmationService commandConfirmationService;
    private DeleteRouter router;

    @BeforeEach
    void setUp() {
        commandValidator = mock(CommandValidator.class);
        commandConfirmationService = mock(CommandConfirmationService.class);
        router = new DeleteRouter(commandValidator, commandConfirmationService);
    }

    @Test
    void tryRoute_blankMessage_shouldNotMatch() {
        DeleteRouter.RouteResult result = router.tryRoute("   ", "alice");

        assertFalse(result.matched());
        assertNull(result.response());
        assertNull(result.command());
    }

    @Test
    void tryRoute_validDeleteIntent_shouldStorePendingConfirmation() {
        when(commandValidator.validate("rm -rf /tmp/work"))
                .thenReturn(CommandValidator.ValidationResult.valid());

        DeleteRouter.RouteResult result = router.tryRoute("請刪除 /tmp/work", "alice");

        assertTrue(result.matched());
        assertTrue(result.response().contains("[CMD:::rm -rf /tmp/work:::]"));
        assertTrue("rm -rf /tmp/work".equals(result.command()));
        verify(commandConfirmationService).storePendingCommand("alice", "rm -rf /tmp/work");
    }

    @Test
    void tryRoute_invalidCommand_shouldReturnValidationErrorWithoutStore() {
        when(commandValidator.validate("rm /tmp/test.txt"))
                .thenReturn(CommandValidator.ValidationResult.invalid("blocked"));

        DeleteRouter.RouteResult result = router.tryRoute("請刪除 /tmp/test.txt", "alice");

        assertTrue(result.matched());
        assertTrue(result.response().contains("blocked"));
        assertNull(result.command());
        verify(commandConfirmationService, never()).storePendingCommand("alice", "rm /tmp/test.txt");
    }

    @Test
    void tryRoute_traversalPath_shouldNormalizeBeforeValidation() {
        when(commandValidator.validate("rm /etc/passwd.txt"))
                .thenReturn(CommandValidator.ValidationResult.invalid("blocked"));

        DeleteRouter.RouteResult result = router.tryRoute("刪除 /home/user/../../../etc/passwd.txt", "alice");

        assertTrue(result.matched());
        assertTrue(result.response().contains("blocked"));
        assertNull(result.command());
        verify(commandValidator).validate("rm /etc/passwd.txt");
        verify(commandValidator, never()).validate("rm /home/user/../../../etc/passwd.txt");
        verify(commandConfirmationService, never()).storePendingCommand("alice", "rm /etc/passwd.txt");
    }
}
