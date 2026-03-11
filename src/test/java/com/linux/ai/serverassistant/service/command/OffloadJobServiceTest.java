package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OffloadJobServiceTest {

    private AdminAuthorizationService adminAuthorizationService;
    private OffloadJobService service;

    @BeforeEach
    void setUp() {
        CommandExecutionService commandExecutionService = mock(CommandExecutionService.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);
        service = new OffloadJobService(commandExecutionService, adminAuthorizationService);
    }

    @Test
    void startOffloadJob_blankUsername_shouldThrow() {
        assertThrows(
                IllegalArgumentException.class,
                () -> service.startOffloadJob(" ", Path.of("/tmp/src"), Path.of("/tmp/dst"), null)
        );
    }

    @Test
    void startOffloadJob_nonAdmin_shouldThrow() {
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> service.startOffloadJob("alice", Path.of("/tmp/src"), Path.of("/tmp/dst"), null)
        );

        assertTrue(ex.getMessage().contains("僅限管理員"));
    }

    @Test
    void getJobProgress_invalidInput_shouldReturnEmpty() {
        assertTrue(service.getJobProgress("alice", " ").isEmpty());
        assertTrue(service.getJobProgress(" ", "job-1").isEmpty());
    }
}
