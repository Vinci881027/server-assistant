package com.linux.ai.serverassistant.controller;

import com.linux.ai.serverassistant.dto.ApiResponse;
import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.AiModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminControllerTest {

    private ChatMessageRepository chatMessageRepository;
    private CommandLogRepository commandLogRepository;
    private AiModelService aiModelService;
    private AdminController controller;

    @BeforeEach
    void setUp() {
        chatMessageRepository = mock(ChatMessageRepository.class);
        commandLogRepository = mock(CommandLogRepository.class);
        aiModelService = mock(AiModelService.class);
        controller = new AdminController(chatMessageRepository, aiModelService, commandLogRepository);
    }

    @Test
    void getAllUsers_shouldReturnApiResponseWithUserList() {
        List<String> usernames = List.of("alice", "bob");
        when(chatMessageRepository.findDistinctUsernames()).thenReturn(usernames);

        ApiResponse<List<String>> response = controller.getAllUsers();

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(usernames, response.getData());
        assertNull(response.getError());
        verify(chatMessageRepository).findDistinctUsernames();
    }

    @Test
    void resetDatabase_shouldReturnApiResponseAndResetData() {
        when(chatMessageRepository.count()).thenReturn(0L);
        when(commandLogRepository.count()).thenReturn(0L);

        ApiResponse<Void> response = controller.resetDatabase();

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals("Database reset successfully", response.getMessage());
        assertNull(response.getData());
        assertNull(response.getError());
        verify(chatMessageRepository).deleteAllInBatch();
        verify(commandLogRepository).deleteAllInBatch();
        verify(aiModelService).deleteAll();
        verify(aiModelService).init();
    }

    @Test
    void getUserHistory_shouldRejectUsernameNotMatchingLinuxRule() {
        ApiResponse<Map<String, Object>> response = controller.getUserHistory("alice@test", 0, 10);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("username 包含不允許的字元", response.getMessage());
    }

    @Test
    void getUserAuditLogs_shouldRejectUsernameNotMatchingLinuxRule() {
        ApiResponse<Map<String, Object>> response = controller.getUserAuditLogs("alice.user", 0, 10);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals("username 包含不允許的字元", response.getMessage());
    }
}
