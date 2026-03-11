package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.entity.ChatMessage;
import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.service.JpaChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private JpaChatMemory jpaChatMemory;
    private ChatMessageRepository chatMessageRepository;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        jpaChatMemory = mock(JpaChatMemory.class);
        chatMessageRepository = mock(ChatMessageRepository.class);
        service = new ConversationService(jpaChatMemory, chatMessageRepository);
    }

    @Test
    void getOrGenerateConversationId_null_shouldGenerateUUID() {
        String id = service.getOrGenerateConversationId(null);
        assertFalse(id.isEmpty());
    }

    @Test
    void getOrGenerateConversationId_existing_shouldReturnSame() {
        assertEquals("my-conv-id", service.getOrGenerateConversationId("my-conv-id"));
    }

    @Nested
    class ResolveConversationIdForStream {

        @Test
        void blankConversationId_shouldGenerateNewId() {
            String id = service.resolveConversationIdForStream("  ", "alice");
            assertFalse(id.isBlank());
        }

        @Test
        void ownedConversation_shouldAllow() {
            when(chatMessageRepository.existsByConversationIdAndUsername("conv-1", "alice")).thenReturn(true);

            String resolved = service.resolveConversationIdForStream("conv-1", "alice");

            assertEquals("conv-1", resolved);
        }

        @Test
        void nonExistingConversation_shouldAllow() {
            when(chatMessageRepository.existsByConversationIdAndUsername("conv-2", "alice")).thenReturn(false);
            when(chatMessageRepository.existsByConversationId("conv-2")).thenReturn(false);

            String resolved = service.resolveConversationIdForStream("conv-2", "alice");

            assertEquals("conv-2", resolved);
        }

        @Test
        void conversationOwnedByOtherUser_shouldThrowAccessDenied() {
            when(chatMessageRepository.existsByConversationIdAndUsername("conv-3", "alice")).thenReturn(false);
            when(chatMessageRepository.existsByConversationId("conv-3")).thenReturn(true);

            assertThrows(AccessDeniedException.class,
                    () -> service.resolveConversationIdForStream("conv-3", "alice"));
        }
    }

    @Nested
    class GetConversationsForUser {

        @Test
        void nullUsername_shouldReturnEmpty() {
            List<Map<String, Object>> result = service.getConversationsForUser(null);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldReturnConversationsWithTruncatedTitles() {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{"conv1", "短標題", LocalDateTime.of(2026, 3, 10, 8, 0), 2L});
            rows.add(new Object[]{"conv2", "這是一個非常非常非常長的對話標題需要被截斷的部分", LocalDateTime.of(2026, 3, 10, 9, 0), 5L});
            when(chatMessageRepository.findConversationSummariesByUsername(eq("alice"), any())).thenReturn(rows);

            List<Map<String, Object>> result = service.getConversationsForUser("alice");
            assertEquals(2, result.size());
            assertEquals("conv1", result.get(0).get("id"));
            assertEquals("短標題", result.get(0).get("title"));
            assertEquals("2026-03-10T08:00", result.get(0).get("updatedAt"));
            assertEquals(2L, result.get(0).get("messageCount"));
            assertEquals("conv2", result.get(1).get("id"));
            assertTrue(((String) result.get(1).get("title")).endsWith("..."));
            assertEquals("2026-03-10T09:00", result.get(1).get("updatedAt"));
            assertEquals(5L, result.get(1).get("messageCount"));
        }

        @Test
        void nullContent_shouldReturnDefaultTitle() {
            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{"conv1", null, null, null});
            when(chatMessageRepository.findConversationSummariesByUsername(eq("alice"), any())).thenReturn(rows);

            List<Map<String, Object>> result = service.getConversationsForUser("alice");
            assertEquals("新對話", result.get(0).get("title"));
            assertEquals(0L, result.get(0).get("messageCount"));
            assertEquals(null, result.get(0).get("updatedAt"));
        }
    }

    @Nested
    class GetRecentHistory {

        @Test
        void validConversation_shouldReturnHistoryPage() {
            LocalDateTime firstAt = LocalDateTime.of(2026, 3, 10, 10, 0, 0);
            LocalDateTime secondAt = LocalDateTime.of(2026, 3, 10, 10, 0, 5);
            when(chatMessageRepository.existsByConversationIdAndUsername("c1", "alice")).thenReturn(true);
            when(chatMessageRepository.countByConversationId("c1")).thenReturn(2L);
            when(chatMessageRepository.findByConversationIdOrderByCreatedAtDescIdDesc(eq("c1"), any(Pageable.class)))
                    .thenReturn(List.of(
                            chatMessage(2L, "c1", "ASSISTANT", "hi there", secondAt),
                            chatMessage(1L, "c1", "USER", "hello", firstAt)
                    ));

            Map<String, Object> result = service.getRecentHistory("c1", "alice", 20, 0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");

            assertEquals(2, messages.size());
            assertEquals("user", messages.get(0).get("role"));
            assertEquals("hello", messages.get(0).get("content"));
            assertEquals("2026-03-10T10:00", messages.get(0).get("createdAt"));
            assertEquals("ai", messages.get(1).get("role"));
            assertEquals("2026-03-10T10:00:05", messages.get(1).get("createdAt"));
            assertEquals(2L, result.get("total"));
            assertEquals(0, result.get("offset"));
            assertEquals(20, result.get("limit"));
        }

        @Test
        void nonOwnedConversation_shouldReturnEmptyPage() {
            when(chatMessageRepository.existsByConversationIdAndUsername("c1", "alice")).thenReturn(false);

            Map<String, Object> result = service.getRecentHistory("c1", "alice", 20, 0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
            assertTrue(messages.isEmpty());
            assertEquals(0L, result.get("total"));
        }

        @Test
        void cursorPagination_shouldReturnCursorFields() {
            LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 3, 10, 12, 30, 0);
            when(chatMessageRepository.existsByConversationIdAndUsername("c1", "alice")).thenReturn(true);
            when(chatMessageRepository.countByConversationId("c1")).thenReturn(2L);
            when(chatMessageRepository.findHistoryPageByCursor(eq("c1"), eq(cursorCreatedAt), eq(99L), any(Pageable.class)))
                    .thenReturn(List.of(
                            chatMessage(80L, "c1", "ASSISTANT", "new", LocalDateTime.of(2026, 3, 10, 12, 0, 5)),
                            chatMessage(79L, "c1", "USER", "old", LocalDateTime.of(2026, 3, 10, 12, 0, 0))
                    ));

            Map<String, Object> result = service.getRecentHistoryByCursor("c1", "alice", 20, cursorCreatedAt, 99L);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");

            assertEquals(2, messages.size());
            assertEquals("user", messages.get(0).get("role"));
            assertEquals("old", messages.get(0).get("content"));
            assertEquals("ai", messages.get(1).get("role"));
            assertEquals("new", messages.get(1).get("content"));
            assertEquals(2L, result.get("total"));
            assertEquals(20, result.get("limit"));
            assertEquals("2026-03-10T12:00", result.get("nextCursorCreatedAt"));
            assertEquals(79L, result.get("nextCursorId"));
        }
    }

    @Test
    void clearHistory_ownedConversation_shouldClear() {
        when(chatMessageRepository.existsByConversationIdAndUsername("c1", "alice")).thenReturn(true);

        service.clearHistory("c1", "alice");
        verify(jpaChatMemory).clear("c1");
    }

    @Test
    void clearHistory_nonOwnedConversation_shouldNotClear() {
        when(chatMessageRepository.existsByConversationIdAndUsername("c1", "alice")).thenReturn(false);

        service.clearHistory("c1", "alice");
        verify(jpaChatMemory, never()).clear(any());
    }

    @Test
    void estimateConversationHistoryChars_ownedConversation_shouldReturnSum() {
        when(chatMessageRepository.existsByConversationIdAndUsername("c1", "alice")).thenReturn(true);
        when(chatMessageRepository.sumContentLengthByConversationId("c1")).thenReturn(123L);

        long result = service.estimateConversationHistoryChars("c1", "alice");

        assertEquals(123L, result);
    }

    @Test
    void estimateConversationHistoryChars_nonOwnedConversation_shouldReturnZero() {
        when(chatMessageRepository.existsByConversationIdAndUsername("c1", "alice")).thenReturn(false);

        long result = service.estimateConversationHistoryChars("c1", "alice");

        assertEquals(0L, result);
        verify(chatMessageRepository, never()).sumContentLengthByConversationId(any());
    }

    @Test
    void assignUsername_shouldUpdateRepository() {
        service.assignUsernameToConversation("c1", "alice");
        verify(chatMessageRepository).updateUsernameByConversationId("c1", "alice");
    }

    @Test
    void persistDeterministicExchange_shouldPersistMessagesAndAssignUsername() {
        service.persistDeterministicExchange("c1", "hello", "hi", "alice");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(jpaChatMemory).add(eq("c1"), messagesCaptor.capture(), eq("alice"));
        verify(chatMessageRepository).updateUsernameByConversationId("c1", "alice");

        List<Message> messages = messagesCaptor.getValue();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0) instanceof UserMessage);
        assertTrue(messages.get(1) instanceof AssistantMessage);
        assertEquals("hello", messages.get(0).getText());
        assertEquals("hi", messages.get(1).getText());
    }

    private static ChatMessage chatMessage(Long id, String conversationId, String messageType, String content, LocalDateTime createdAt) {
        ChatMessage msg = new ChatMessage();
        msg.setId(id);
        msg.setConversationId(conversationId);
        msg.setMessageType(messageType);
        msg.setContent(content);
        msg.setCreatedAt(createdAt);
        return msg;
    }
}
