package com.linux.ai.serverassistant.service;

import com.linux.ai.serverassistant.entity.ChatMessage;
import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class JpaChatMemoryTest {

    private ChatMessageRepository repository;
    private UserContext userContext;
    private JpaChatMemory chatMemory;
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        userContext = new UserContext();
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        chatMemory = new JpaChatMemory(repository, userContext, transactionManager);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
    }

    // ========== add ==========

    @Test
    void add_shouldPersistWithJpaRepository() {
        chatMemory.add("c1", List.of(new UserMessage("hello")), "alice");

        verify(repository).saveAll(argThat(entities ->
                entities != null && StreamSupport.stream(entities.spliterator(), false).count() == 1));
    }

    @Test
    void add_whenMaxMessagesPerConversationDisabled_shouldSkipTrim() {
        ReflectionTestUtils.setField(chatMemory, "maxMessagesPerConversation", 0);

        chatMemory.add("c1", List.of(new UserMessage("hello")), "alice");

        verify(repository).saveAll(anyList());
        verify(repository, never()).trimConversationToLatest(anyString(), anyInt());
    }

    @Test
    void add_withUsernameOverride_shouldPreferOverrideEvenWhenSessionUserExists() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true).setAttribute("user", "session-user");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        chatMemory.add("c1", List.of(new UserMessage("hello")), "override-user");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> entitiesCaptor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(entitiesCaptor.capture());

        List<ChatMessage> entities = entitiesCaptor.getValue();
        assertNotNull(entities);
        assertFalse(entities.isEmpty());
        assertEquals("override-user", entities.get(0).getUsername());
    }

    @Test
    void add_concurrentSameConversation_shouldNotRunTrimInParallel() throws Exception {
        ReflectionTestUtils.setField(chatMemory, "maxMessagesPerConversation", 10);
        List<Message> batch = IntStream.range(0, 20)
                .mapToObj(i -> (Message) new UserMessage("m" + i))
                .toList();
        CyclicBarrier saveBarrier = new CyclicBarrier(2);
        AtomicInteger inFlightTrimCalls = new AtomicInteger(0);
        AtomicInteger maxConcurrentTrimCalls = new AtomicInteger(0);

        doAnswer(invocation -> {
            saveBarrier.await(2, TimeUnit.SECONDS);
            return invocation.getArgument(0);
        }).when(repository).saveAll(anyList());

        doAnswer(invocation -> {
            int inFlight = inFlightTrimCalls.incrementAndGet();
            maxConcurrentTrimCalls.accumulateAndGet(inFlight, Math::max);
            try {
                Thread.sleep(120);
            } finally {
                inFlightTrimCalls.decrementAndGet();
            }
            return 1;
        }).when(repository).trimConversationToLatest("c1", 10);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> chatMemory.add("c1", batch, "alice"));
            Future<?> second = executor.submit(() -> chatMemory.add("c1", batch, "alice"));
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, maxConcurrentTrimCalls.get());
        verify(repository, times(2)).trimConversationToLatest("c1", 10);
    }

    // ========== get ==========

    @Test
    void get_shouldReturnAllMessagesInAscendingOrder() {
        ChatMessage first = new ChatMessage();
        first.setMessageType("USER");
        first.setContent("hello");

        ChatMessage second = new ChatMessage();
        second.setMessageType("ASSISTANT");
        second.setContent("hi");

        when(repository.findByConversationIdOrderByCreatedAtDescIdDesc(eq("c1"), any(Pageable.class)))
                .thenReturn(List.of(second, first));

        List<Message> messages = chatMemory.get("c1");

        assertEquals(2, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertEquals("hello", messages.get(0).getText());
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertEquals("hi", messages.get(1).getText());
        verify(repository).findByConversationIdOrderByCreatedAtDescIdDesc(
                eq("c1"),
                argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 200)
        );
    }

    @Test
    void get_blankConversationId_shouldReturnEmpty() {
        assertTrue(chatMemory.get(" ").isEmpty());
        verify(repository, never()).findByConversationIdOrderByCreatedAtDescIdDesc(anyString(), any(Pageable.class));
    }

    @Test
    void getWithLastN_unknownType_shouldDefaultToUserMessage() {
        ChatMessage msg = new ChatMessage();
        msg.setMessageType("UNKNOWN");
        msg.setContent("test");

        when(repository.findByConversationIdOrderByCreatedAtDescIdDesc(eq("c1"), any(Pageable.class)))
                .thenReturn(List.of(msg));

        List<Message> messages = chatMemory.get("c1", 1);
        assertEquals(1, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
    }

    @Test
    void getWithLastN_nullType_shouldDefaultToUserMessage() {
        ChatMessage msg = new ChatMessage();
        msg.setMessageType(null);
        msg.setContent("test");

        when(repository.findByConversationIdOrderByCreatedAtDescIdDesc(eq("c1"), any(Pageable.class)))
                .thenReturn(List.of(msg));

        List<Message> messages = chatMemory.get("c1", 1);
        assertInstanceOf(UserMessage.class, messages.get(0));
    }

    @Test
    void getWithLastN_shouldMapMessageTypes() {
        ChatMessage newestSystem = new ChatMessage();
        newestSystem.setMessageType("SYSTEM");
        newestSystem.setContent("system prompt");

        ChatMessage middleAssistant = new ChatMessage();
        middleAssistant.setMessageType("ASSISTANT");
        middleAssistant.setContent("hi there");

        ChatMessage oldestUser = new ChatMessage();
        oldestUser.setMessageType("USER");
        oldestUser.setContent("hello");

        when(repository.findByConversationIdOrderByCreatedAtDescIdDesc(eq("c1"), any(Pageable.class)))
                .thenReturn(List.of(newestSystem, middleAssistant, oldestUser));

        List<Message> messages = chatMemory.get("c1", 3);
        assertEquals(3, messages.size());
        assertInstanceOf(UserMessage.class, messages.get(0));
        assertInstanceOf(AssistantMessage.class, messages.get(1));
        assertInstanceOf(SystemMessage.class, messages.get(2));
    }

    // ========== get with lastN ==========

    @Test
    void getWithLastN_shouldReturnLastNMessages() {
        ChatMessage newest = new ChatMessage();
        newest.setMessageType("USER");
        newest.setContent("msg4");
        ChatMessage older = new ChatMessage();
        older.setMessageType("USER");
        older.setContent("msg3");

        when(repository.findByConversationIdOrderByCreatedAtDescIdDesc(eq("c1"), any(Pageable.class)))
                .thenReturn(List.of(newest, older));

        List<Message> messages = chatMemory.get("c1", 2);
        assertEquals(2, messages.size());
        assertEquals("msg3", messages.get(0).getText());
        assertEquals("msg4", messages.get(1).getText());
    }

    @Test
    void getWithLastN_zero_shouldReturnEmpty() {
        List<Message> messages = chatMemory.get("c1", 0);
        assertTrue(messages.isEmpty());
    }

    @Test
    void getWithLastN_negative_shouldReturnEmpty() {
        List<Message> messages = chatMemory.get("c1", -1);
        assertTrue(messages.isEmpty());
    }

    @Test
    void getWithOffset_notMultipleOfLimit_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () -> chatMemory.get("c1", 50, 25));
        verify(repository, never()).findByConversationIdOrderByCreatedAtDescIdDesc(anyString(), any(Pageable.class));
    }

    @Test
    void getByCursor_shouldReturnMessagesAndNextCursor() {
        LocalDateTime newestAt = LocalDateTime.of(2026, 3, 10, 9, 0, 0);
        LocalDateTime oldestAt = LocalDateTime.of(2026, 3, 10, 8, 30, 0);

        ChatMessage newest = new ChatMessage();
        newest.setId(200L);
        newest.setMessageType("ASSISTANT");
        newest.setContent("newest");
        newest.setCreatedAt(newestAt);

        ChatMessage oldest = new ChatMessage();
        oldest.setId(150L);
        oldest.setMessageType("USER");
        oldest.setContent("oldest");
        oldest.setCreatedAt(oldestAt);

        when(repository.findHistoryPageByCursor(eq("c1"), eq(newestAt), eq(200L), any(Pageable.class)))
                .thenReturn(List.of(newest, oldest));

        JpaChatMemory.CursorPage cursorPage = chatMemory.getByCursor("c1", 50, newestAt, 200L);

        assertEquals(2, cursorPage.messages().size());
        assertEquals("oldest", cursorPage.messages().get(0).getText());
        assertEquals("newest", cursorPage.messages().get(1).getText());
        assertEquals(oldestAt, cursorPage.nextCursorCreatedAt());
        assertEquals(150L, cursorPage.nextCursorId());
        verify(repository).findHistoryPageByCursor(
                eq("c1"),
                eq(newestAt),
                eq(200L),
                argThat(pageable -> pageable.getPageNumber() == 0 && pageable.getPageSize() == 50)
        );
    }

    @Test
    void getByCursor_missingCursorPair_shouldThrow() {
        LocalDateTime cursorCreatedAt = LocalDateTime.of(2026, 3, 10, 8, 30, 0);

        assertThrows(IllegalArgumentException.class, () -> chatMemory.getByCursor("c1", 50, cursorCreatedAt, null));
        verify(repository, never()).findHistoryPageByCursor(anyString(), any(), any(), any(Pageable.class));
    }

    @Test
    void getWithLastN_largerThanSize_shouldReturnAll() {
        ChatMessage m = new ChatMessage();
        m.setMessageType("USER");
        m.setContent("only");

        when(repository.findByConversationIdOrderByCreatedAtDescIdDesc(eq("c1"), any(Pageable.class)))
                .thenReturn(List.of(m));

        List<Message> messages = chatMemory.get("c1", 100);
        assertEquals(1, messages.size());
    }

    // ========== clear ==========

    @Test
    void clear_shouldDelegateToRepository() {
        chatMemory.clear("c1");
        verify(repository).deleteByConversationId("c1");
    }

    // ========== deleteLastMessages ==========

    @Test
    void deleteLastMessages_shouldDeleteLastN() {
        chatMemory.deleteLastMessages("c1", 2, "alice");

        verify(repository).deleteLastByConversationIdAndUsername("c1", "alice", 2);
    }

    @Test
    void deleteLastMessages_nullConversationId_shouldDoNothing() {
        chatMemory.deleteLastMessages(null, 2, "alice");
        verify(repository, never()).deleteLastByConversationIdAndUsername(any(), any(), anyInt());
    }

    @Test
    void deleteLastMessages_zeroCount_shouldDoNothing() {
        chatMemory.deleteLastMessages("c1", 0, "alice");
        verify(repository, never()).deleteLastByConversationIdAndUsername(any(), any(), anyInt());
    }

    @Test
    void deleteLastMessages_emptyConversation_shouldInvokeScopedDelete() {
        chatMemory.deleteLastMessages("c1", 2, "alice");
        verify(repository).deleteLastByConversationIdAndUsername("c1", "alice", 2);
    }

    @Test
    void deleteLastMessages_shouldUseProvidedUsernameScope() {
        chatMemory.deleteLastMessages("c1", 2, "  bob  ");
        verify(repository).deleteLastByConversationIdAndUsername("c1", "bob", 2);
    }

    @Test
    void deleteLastMessages_blankUsername_shouldDoNothing() {
        chatMemory.deleteLastMessages("c1", 2, "  ");

        verify(repository, never()).deleteLastByConversationIdAndUsername(any(), any(), anyInt());
    }

    // ========== replaceLastAssistantMessage ==========

    @Test
    void replaceLastAssistantMessage_shouldUpdateIfLastIsAssistant() {
        ChatMessage msg = new ChatMessage();
        msg.setId(1L);
        msg.setMessageType("ASSISTANT");
        msg.setContent("old");

        when(repository.findTop1ByConversationIdOrderByCreatedAtDescIdDesc("c1")).thenReturn(Optional.of(msg));

        chatMemory.replaceLastAssistantMessage("c1", "new content");

        verify(repository).save(argThat(m -> "new content".equals(m.getContent())));
    }

    @Test
    void replaceLastAssistantMessage_shouldAppendIfLastIsNotAssistant() {
        ChatMessage msg = new ChatMessage();
        msg.setMessageType("USER");
        msg.setContent("user msg");

        when(repository.findTop1ByConversationIdOrderByCreatedAtDescIdDesc("c1")).thenReturn(Optional.of(msg));

        chatMemory.replaceLastAssistantMessage("c1", "new assistant msg");

        verify(repository).saveAll(argThat(entities ->
                entities != null && StreamSupport.stream(entities.spliterator(), false).count() == 1));
        verify(repository, never()).save(argThat(m -> "ASSISTANT".equalsIgnoreCase(m.getMessageType())));
    }

    @Test
    void replaceLastAssistantMessage_nullConversationId_shouldDoNothing() {
        chatMemory.replaceLastAssistantMessage(null, "content");
        verify(repository, never()).findTop1ByConversationIdOrderByCreatedAtDescIdDesc(any());
    }

    // ========== replaceLatestAssistantCommandPrompt ==========

    @Test
    void replaceLatestAssistantCommandPrompt_shouldReplaceMatchingPrompt() {
        ChatMessage prompt = new ChatMessage();
        prompt.setMessageType("assistant");
        prompt.setContent("請確認 [CMD:::rm -rf /tmp/demo:::]");

        when(repository.findLatestByConversationIdAndMessageTypeAndContentMarkers(
                eq("c1"),
                eq("ASSISTANT"),
                eq("[CMD:::rm -rf /tmp/demo:::]"),
                eq("[CONFIRM_CMD:::rm -rf /tmp/demo:::]")))
                .thenReturn(List.of(prompt));

        boolean replaced = chatMemory.replaceLatestAssistantCommandPrompt("c1", "rm -rf /tmp/demo", "已取消");

        assertTrue(replaced);
        verify(repository).save(argThat(m -> "已取消".equals(m.getContent())));
    }

    @Test
    void replaceLatestAssistantCommandPrompt_shouldSkipNonMatchingCandidateAndReplaceLaterMatch() {
        ChatMessage nonMatching = new ChatMessage();
        nonMatching.setMessageType("assistant");
        nonMatching.setContent("沒有目標 marker");

        ChatMessage matching = new ChatMessage();
        matching.setMessageType("assistant");
        matching.setContent("確認 [CONFIRM_CMD:::ls -la:::]");

        when(repository.findLatestByConversationIdAndMessageTypeAndContentMarkers(
                eq("c1"),
                eq("ASSISTANT"),
                eq("[CMD:::ls -la:::]"),
                eq("[CONFIRM_CMD:::ls -la:::]")))
                .thenReturn(List.of(nonMatching, matching));

        boolean replaced = chatMemory.replaceLatestAssistantCommandPrompt("c1", "ls -la", "done");

        assertTrue(replaced);
        verify(repository).save(argThat(m -> "done".equals(m.getContent())));
    }

    @Test
    void replaceLatestAssistantCommandPrompt_shouldReturnFalseWhenNoCandidate() {
        when(repository.findLatestByConversationIdAndMessageTypeAndContentMarkers(
                eq("c1"),
                eq("ASSISTANT"),
                eq("[CMD:::ls -la:::]"),
                eq("[CONFIRM_CMD:::ls -la:::]")))
                .thenReturn(List.of());

        boolean replaced = chatMemory.replaceLatestAssistantCommandPrompt("c1", "ls -la", "done");

        assertFalse(replaced);
        verify(repository, never()).save(any(ChatMessage.class));
    }
}
