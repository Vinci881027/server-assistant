package com.linux.ai.serverassistant.controller;

import com.linux.ai.serverassistant.config.AiModelProperties;
import com.linux.ai.serverassistant.dto.ApiResponse;
import com.linux.ai.serverassistant.dto.CancelCommandRequest;
import com.linux.ai.serverassistant.dto.ChatStreamRequest;
import com.linux.ai.serverassistant.dto.ConfirmCommandRequest;
import com.linux.ai.serverassistant.exception.ConcurrentStreamLimitExceededException;
import com.linux.ai.serverassistant.exception.RateLimitExceededException;
import com.linux.ai.serverassistant.service.AiModelService;
import com.linux.ai.serverassistant.service.ai.ChatService;
import com.linux.ai.serverassistant.service.ai.ConversationService;
import com.linux.ai.serverassistant.service.command.CommandJobService;
import com.linux.ai.serverassistant.service.command.OffloadJobService;
import com.linux.ai.serverassistant.service.security.ChatRateLimiter;
import com.linux.ai.serverassistant.service.security.SlashCommandRateLimiter;
import com.linux.ai.serverassistant.service.security.TpmBucket;
import com.linux.ai.serverassistant.service.security.UserTpmLimiter;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerTest {

    private static final long IN_FLIGHT_TTL_MS = 10_000L;
    private static final int MAX_CONCURRENT_STREAMS = 2;

    private ChatService chatService;
    private ConversationService conversationService;
    private AiModelService aiModelService;
    private AiModelProperties aiModelProperties;
    private ChatRateLimiter chatRateLimiter;
    private SlashCommandRateLimiter slashCommandRateLimiter;
    private UserTpmLimiter userTpmLimiter;
    private TpmBucket tpmBucket;
    private HttpSession session;
    private AtomicLong currentTimeMs;
    private ChatController chatController;

    @BeforeEach
    void setUp() {
        chatService = mock(ChatService.class);
        conversationService = mock(ConversationService.class);
        aiModelService = mock(AiModelService.class);
        aiModelProperties = new AiModelProperties();
        OffloadJobService offloadJobService = mock(OffloadJobService.class);
        CommandJobService commandJobService = mock(CommandJobService.class);
        chatRateLimiter = mock(ChatRateLimiter.class);
        slashCommandRateLimiter = mock(SlashCommandRateLimiter.class);
        userTpmLimiter = mock(UserTpmLimiter.class);
        tpmBucket = mock(TpmBucket.class);

        currentTimeMs = new AtomicLong(1_000_000L);
        chatController = new ChatController(
                chatService,
                conversationService,
                aiModelService,
                aiModelProperties,
                offloadJobService,
                commandJobService,
                chatRateLimiter,
                slashCommandRateLimiter,
                userTpmLimiter,
                tpmBucket,
                currentTimeMs::get,
                IN_FLIGHT_TTL_MS,
                MAX_CONCURRENT_STREAMS);

        session = mock(HttpSession.class);
        when(session.getAttribute("user")).thenReturn("alice");
        when(session.getId()).thenReturn("sid-1");
        when(chatRateLimiter.tryConsume("alice")).thenReturn(0L);
        when(slashCommandRateLimiter.tryConsume("alice")).thenReturn(0L);
        when(aiModelService.countHealthyGroqApiKeys()).thenReturn(1);
        when(userTpmLimiter.tryConsumeWithHandle(eq("alice"), anyLong(), anyLong()))
                .thenReturn(new UserTpmLimiter.ConsumeResult(0L, 11L));
        when(userTpmLimiter.adjustEventTokens(anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(conversationService.resolveConversationIdForStream(any(), eq("alice")))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(conversationService.getRecentHistoryByCursor(anyString(), anyString(), anyInt(), any(), any()))
                .thenReturn(Map.of("messages", java.util.List.of(), "total", 0L, "limit", 100));
        when(tpmBucket.peek(anyString(), anyInt(), anyLong())).thenReturn(0L);
        when(tpmBucket.consumeWithResult(anyString(), anyInt(), anyLong()))
                .thenReturn(new TpmBucket.ConsumeResult(0L, 2_103L));
        when(tpmBucket.adjustConsumption(anyString(), anyInt(), anyLong()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenReturn(Flux.just("ok"));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));
    }

    @Test
    void streamChat_shouldRejectWhenConcurrentStreamLimitExceeded() {
        ChatStreamRequest request = streamRequest();
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenReturn(Flux.never());

        chatController.streamChat(request, session);
        chatController.streamChat(request, session);
        currentTimeMs.addAndGet(8_000L);

        ConcurrentStreamLimitExceededException exception = assertThrows(
                ConcurrentStreamLimitExceededException.class,
                () -> chatController.streamChat(request, session));

        assertEquals("您已有對話進行中，請等待完成。", exception.getMessage());
        assertEquals(ChatService.STREAM_TIMEOUT_SECONDS - 8L, exception.getRetryAfterSeconds());
        verify(chatService, times(2)).streamChat(
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(java.util.function.LongConsumer.class));
    }

    @Test
    void streamChat_shouldReleaseActiveSlotWhenStreamCompletes() {
        ChatStreamRequest request = streamRequest();
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenReturn(Flux.just("chunk"));

        chatController.streamChat(request, session).collectList().block();

        assertFalse(activeStreamsPerUser().containsKey("alice"));
    }

    @Test
    void streamChat_shouldReleaseActiveSlotWhenServiceThrows() {
        ChatStreamRequest request = streamRequest();
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenThrow(new IllegalStateException("boom"));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> chatController.streamChat(request, session));

        assertEquals("boom", exception.getMessage());
        assertFalse(activeStreamsPerUser().containsKey("alice"));
    }

    @Test
    void streamChat_shouldReleaseActiveSlotWhenFallbackRateLimitRejected_shouldRollbackUserTpm() {
        ChatStreamRequest request = streamRequest();
        when(chatRateLimiter.tryConsume("alice")).thenReturn(5L);
        when(userTpmLimiter.rollback("alice", 11L)).thenReturn(true);

        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> chatController.streamChat(request, session));

        assertEquals("請求過於頻繁，請稍後再試。", exception.getMessage());
        assertEquals(RateLimitExceededException.REASON_USER_RATE_LIMIT, exception.getReason());
        assertFalse(activeStreamsPerUser().containsKey("alice"));
        verify(chatService, never()).streamChat(
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(java.util.function.LongConsumer.class));
        verify(userTpmLimiter).tryConsumeWithHandle(anyString(), anyLong(), anyLong());
        verify(tpmBucket).peek(anyString(), anyInt(), anyLong());
        verify(tpmBucket, never()).consumeWithResult(anyString(), anyInt(), anyLong());
        verify(userTpmLimiter).rollback("alice", 11L);
    }

    @Test
    void streamChat_shouldReleaseActiveSlotWhenUserTpmRejected() {
        ChatStreamRequest request = streamRequest();
        when(userTpmLimiter.tryConsumeWithHandle(eq("alice"), anyLong(), anyLong()))
                .thenReturn(new UserTpmLimiter.ConsumeResult(6L, 0L));

        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> chatController.streamChat(request, session));

        assertEquals("請求過於頻繁，請稍後再試。", exception.getMessage());
        assertEquals(RateLimitExceededException.REASON_USER_TPM_LIMIT, exception.getReason());
        assertFalse(activeStreamsPerUser().containsKey("alice"));
        verify(chatService, never()).streamChat(
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(java.util.function.LongConsumer.class));
        verify(chatRateLimiter, never()).tryConsume(anyString());
        verify(tpmBucket, never()).consumeWithResult(anyString(), anyInt(), anyLong());
    }

    @Test
    void streamChat_shouldBypassChatRateLimiterForSlashCommand() {
        ChatStreamRequest request = streamRequest();
        request.setMessage("/status");
        when(chatRateLimiter.tryConsume("alice")).thenReturn(5L);
        when(slashCommandRateLimiter.tryConsume("alice")).thenReturn(0L);
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(slashCommandRateLimiter).tryConsume("alice");
        verify(chatRateLimiter, never()).tryConsume("alice");
        verify(chatService).streamChat(eq("/status"), eq((String) null), anyString(), eq("alice"), eq("sid-1"));
    }

    @Test
    void streamChat_shouldBypassChatRateLimiterForExclamationCommand() {
        ChatStreamRequest request = streamRequest();
        request.setMessage("!docker ps");
        when(chatRateLimiter.tryConsume("alice")).thenReturn(5L);
        when(slashCommandRateLimiter.tryConsume("alice")).thenReturn(0L);
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(slashCommandRateLimiter).tryConsume("alice");
        verify(chatRateLimiter, never()).tryConsume("alice");
        verify(chatService).streamChat(eq("!docker ps"), eq((String) null), anyString(), eq("alice"), eq("sid-1"));
    }

    @Test
    void streamChat_shouldReleaseActiveSlotWhenSlashCommandRateLimitRejected() {
        ChatStreamRequest request = streamRequest();
        request.setMessage("/docker");
        when(slashCommandRateLimiter.tryConsume("alice")).thenReturn(5L);

        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> chatController.streamChat(request, session));

        assertEquals("請求過於頻繁，請稍後再試。", exception.getMessage());
        assertEquals(RateLimitExceededException.REASON_USER_RATE_LIMIT, exception.getReason());
        assertFalse(activeStreamsPerUser().containsKey("alice"));
        verify(chatService, never()).streamChat(anyString(), any(), anyString(), anyString(), anyString());
        verify(chatRateLimiter, never()).tryConsume("alice");
        verify(userTpmLimiter, never()).tryConsumeWithHandle(anyString(), anyLong(), anyLong());
        verify(tpmBucket, never()).peek(anyString(), anyInt(), anyLong());
        verify(tpmBucket, never()).consumeWithResult(anyString(), anyInt(), anyLong());
    }

    @Test
    void streamChat_shouldReleaseActiveSlotWhenGlobalTpmRejectedBeforeUserConsume() {
        ChatStreamRequest request = streamRequest();
        when(tpmBucket.peek(anyString(), anyInt(), anyLong())).thenReturn(7L);

        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> chatController.streamChat(request, session));

        assertEquals("請求過於頻繁，請稍後再試。", exception.getMessage());
        assertEquals(RateLimitExceededException.REASON_GLOBAL_TPM_LIMIT, exception.getReason());
        assertFalse(activeStreamsPerUser().containsKey("alice"));
        verify(chatService, never()).streamChat(
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(java.util.function.LongConsumer.class));
        verify(userTpmLimiter, never()).tryConsumeWithHandle(anyString(), anyLong(), anyLong());
        verify(tpmBucket, never()).consumeWithResult(anyString(), anyInt(), anyLong());
        verify(userTpmLimiter, never()).rollback(anyString(), anyLong());
    }

    @Test
    void streamChat_whenNoHealthyGroqApiKey_shouldRejectBeforeTpmConsume() {
        ChatStreamRequest request = streamRequest();
        when(aiModelService.countHealthyGroqApiKeys()).thenReturn(0);
        when(aiModelService.getKeyCooldownMs()).thenReturn(12_345L);

        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> chatController.streamChat(request, session));

        assertEquals(RateLimitExceededException.REASON_GLOBAL_TPM_LIMIT, exception.getReason());
        assertEquals(13L, exception.getRetryAfterSeconds());
        assertFalse(activeStreamsPerUser().containsKey("alice"));
        verify(userTpmLimiter, never()).tryConsumeWithHandle(anyString(), anyLong(), anyLong());
        verify(tpmBucket, never()).peek(anyString(), anyInt(), anyLong());
        verify(tpmBucket, never()).consumeWithResult(anyString(), anyInt(), anyLong());
        verify(chatService, never()).streamChat(
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(java.util.function.LongConsumer.class));
    }

    @Test
    void streamChat_whenGlobalConsumeRejectedAfterUserConsume_shouldRollbackUserTpm() {
        ChatStreamRequest request = streamRequest();
        when(userTpmLimiter.tryConsumeWithHandle(eq("alice"), anyLong(), anyLong()))
                .thenReturn(new UserTpmLimiter.ConsumeResult(0L, 77L));
        when(tpmBucket.consumeWithResult(anyString(), anyInt(), anyLong()))
                .thenReturn(new TpmBucket.ConsumeResult(7L, 0L));
        when(userTpmLimiter.rollback("alice", 77L)).thenReturn(true);

        RateLimitExceededException exception = assertThrows(
                RateLimitExceededException.class,
                () -> chatController.streamChat(request, session));

        assertEquals(RateLimitExceededException.REASON_GLOBAL_TPM_LIMIT, exception.getReason());
        verify(userTpmLimiter).rollback("alice", 77L);
    }

    @Test
    void streamChat_shouldPassEstimatedTokensAndModelTpmToBucket() {
        ChatStreamRequest request = streamRequest();
        request.setConversationId("conv-1");
        request.setModel("120b");

        AiModelProperties.ModelConfig modelConfig = new AiModelProperties.ModelConfig();
        modelConfig.setTpm(9_000);
        when(aiModelService.getModelsAsMap()).thenReturn(Map.of("120b", modelConfig));
        when(conversationService.getRecentHistoryByCursor(eq("conv-1"), eq("alice"), anyInt(), any(), any()))
                .thenReturn(historyEstimatePage("a".repeat(1_203)));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(tpmBucket).peek("120b", 9_000, 2_204L);
        verify(tpmBucket).consumeWithResult("120b", 9_000, 2_204L);
        verify(chatService).streamChat(
                eq("hello"),
                eq("conv-1"),
                eq("120b"),
                eq("alice"),
                eq("sid-1"),
                any(java.util.function.LongConsumer.class));
    }

    @Test
    void streamChat_whenUsageReported_shouldApplyDeltaCorrection() {
        ChatStreamRequest request = streamRequest();
        when(userTpmLimiter.tryConsumeWithHandle(eq("alice"), anyLong(), anyLong()))
                .thenReturn(new UserTpmLimiter.ConsumeResult(0L, 99L, 1_802L));
        when(tpmBucket.consumeWithResult(anyString(), anyInt(), anyLong()))
                .thenReturn(new TpmBucket.ConsumeResult(0L, 1_802L));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenAnswer(invocation -> {
                    java.util.function.LongConsumer usageConsumer = invocation.getArgument(5);
                    usageConsumer.accept(300L);
                    return Flux.just("ok");
                });

        chatController.streamChat(request, session).collectList().block();

        verify(userTpmLimiter).adjustEventTokens("alice", 99L, -1_502L);
        verify(tpmBucket).adjustConsumption(anyString(), anyInt(), eq(-1_502L));
        verify(userTpmLimiter, never()).rollback("alice", 99L);
    }

    @Test
    void streamChat_whenUsageMissing_shouldEstimateRefundFromStreamedChars() {
        ChatStreamRequest request = streamRequest();
        when(userTpmLimiter.tryConsumeWithHandle(eq("alice"), anyLong(), anyLong()))
                .thenReturn(new UserTpmLimiter.ConsumeResult(0L, 123L, 1_802L));
        when(tpmBucket.consumeWithResult(anyString(), anyInt(), anyLong()))
                .thenReturn(new TpmBucket.ConsumeResult(0L, 1_802L));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(userTpmLimiter).adjustEventTokens("alice", 123L, -599L);
        verify(userTpmLimiter, never()).rollback("alice", 123L);
        verify(tpmBucket).adjustConsumption(anyString(), anyInt(), eq(-599L));
    }

    @Test
    void streamChat_whenZeroUsageReported_shouldFullyRefundReservations() {
        ChatStreamRequest request = streamRequest();
        when(userTpmLimiter.tryConsumeWithHandle(eq("alice"), anyLong(), anyLong()))
                .thenReturn(new UserTpmLimiter.ConsumeResult(0L, 123L, 1_802L));
        when(tpmBucket.consumeWithResult(anyString(), anyInt(), anyLong()))
                .thenReturn(new TpmBucket.ConsumeResult(0L, 1_802L));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenAnswer(invocation -> {
                    java.util.function.LongConsumer usageConsumer = invocation.getArgument(5);
                    usageConsumer.accept(0L);
                    return Flux.just("[RATE_LIMIT:::90:::0:::]");
                });

        chatController.streamChat(request, session).collectList().block();

        verify(userTpmLimiter).adjustEventTokens("alice", 123L, -1_802L);
        verify(tpmBucket).adjustConsumption(anyString(), anyInt(), eq(-1_802L));
        verify(userTpmLimiter, never()).rollback("alice", 123L);
    }

    @Test
    void streamChat_whenUsageMissingAndStreamedOutputCoversReserve_shouldSkipRefund() {
        ChatStreamRequest request = streamRequest();
        when(userTpmLimiter.tryConsumeWithHandle(eq("alice"), anyLong(), anyLong()))
                .thenReturn(new UserTpmLimiter.ConsumeResult(0L, 123L, 1_802L));
        when(tpmBucket.consumeWithResult(anyString(), anyInt(), anyLong()))
                .thenReturn(new TpmBucket.ConsumeResult(0L, 1_802L));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString(), any(java.util.function.LongConsumer.class)))
                .thenReturn(Flux.just("x".repeat(2_400)));

        chatController.streamChat(request, session).collectList().block();

        verify(userTpmLimiter, never()).adjustEventTokens(anyString(), anyLong(), anyLong());
        verify(tpmBucket, never()).adjustConsumption(anyString(), anyInt(), anyLong());
    }

    @Test
    void streamChat_shouldCapHistoryCharsForTokenEstimation() {
        ChatStreamRequest request = streamRequest();
        request.setConversationId("conv-1");
        when(conversationService.getRecentHistoryByCursor(eq("conv-1"), eq("alice"), anyInt(), any(), any()))
                .thenReturn(historyEstimatePage("a".repeat(100_000)));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(tpmBucket).peek(anyString(), anyInt(), eq(5_803L));
        verify(tpmBucket).consumeWithResult(anyString(), anyInt(), eq(5_803L));
    }

    @Test
    void streamChat_shouldEstimateHigherTokenCostForCjkHistory() {
        ChatStreamRequest request = streamRequest();
        request.setConversationId("conv-1");
        when(conversationService.getRecentHistoryByCursor(eq("conv-1"), eq("alice"), anyInt(), any(), any()))
                .thenReturn(historyEstimatePage("你".repeat(100)));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(tpmBucket).peek(anyString(), anyInt(), eq(2_003L));
        verify(tpmBucket).consumeWithResult(anyString(), anyInt(), eq(2_003L));
    }

    @Test
    void streamChat_shouldScaleUserAndGlobalTpmByHealthyGroqKeyCount() {
        ChatStreamRequest request = streamRequest();
        request.setConversationId("conv-1");
        request.setModel("120b");

        AiModelProperties.ModelConfig modelConfig = new AiModelProperties.ModelConfig();
        modelConfig.setTpm(9_000);
        when(aiModelService.getModelsAsMap()).thenReturn(Map.of("120b", modelConfig));
        when(aiModelService.countHealthyGroqApiKeys()).thenReturn(3);
        when(conversationService.getRecentHistoryByCursor(eq("conv-1"), eq("alice"), anyInt(), any(), any()))
                .thenReturn(historyEstimatePage("a".repeat(1_203)));
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(userTpmLimiter).tryConsumeWithHandle("alice", 2_204L, 27_000L);
        verify(tpmBucket).peek("120b", 27_000, 2_204L);
        verify(tpmBucket).consumeWithResult("120b", 27_000, 2_204L);
    }

    @Test
    void streamChat_shouldUseLowerOutputReserveForSlashCommand() {
        ChatStreamRequest request = streamRequest();
        request.setMessage("/status");
        request.setConversationId("conv-1");
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(tpmBucket, never()).peek(anyString(), anyInt(), anyLong());
        verify(tpmBucket, never()).consumeWithResult(anyString(), anyInt(), anyLong());
    }

    @Test
    void streamChat_shouldEstimateHigherTokenCostForCjkMessage() {
        ChatStreamRequest request = streamRequest();
        request.setMessage("你好世界");
        request.setConversationId("conv-1");
        when(chatService.streamChat(anyString(), any(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just("ok"));

        chatController.streamChat(request, session).collectList().block();

        verify(tpmBucket).peek(anyString(), anyInt(), eq(1_809L));
        verify(tpmBucket).consumeWithResult(anyString(), anyInt(), eq(1_809L));
    }

    @Test
    void streamChat_whenConversationOwnershipRejected_shouldNotConsumeGlobalBucket() {
        ChatStreamRequest request = streamRequest();
        request.setConversationId("conv-foreign");
        when(conversationService.resolveConversationIdForStream("conv-foreign", "alice"))
                .thenThrow(new AccessDeniedException("無權存取該對話"));

        assertThrows(AccessDeniedException.class, () -> chatController.streamChat(request, session));

        verify(tpmBucket, never()).peek(anyString(), anyInt(), anyLong());
        verify(tpmBucket, never()).consumeWithResult(anyString(), anyInt(), anyLong());
        verify(chatService, never()).streamChat(
                anyString(),
                any(),
                anyString(),
                anyString(),
                anyString(),
                any(java.util.function.LongConsumer.class));
    }

    @Test
    void confirmCommand_shouldRejectWhenInFlightKeyNotExpired() {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("rm -rf /tmp/demo");

        String key = "confirm:alice:rm -rf /tmp/demo";
        inFlightConfirmKeys().put(key, currentTimeMs.get() - 2_000L);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> chatController.confirmCommand(request, session));

        assertEquals("此指令正在處理中，請勿重複送出。", exception.getMessage());
        verify(chatService, never()).executeConfirmedCommand(anyString(), anyString(), any(), anyString());
    }

    @Test
    void confirmCommand_shouldRecoverWhenInFlightKeyExpired() {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("rm -rf /tmp/demo");

        String key = "confirm:alice:rm -rf /tmp/demo";
        inFlightConfirmKeys().put(key, currentTimeMs.get() - IN_FLIGHT_TTL_MS - 1L);
        when(chatService.executeConfirmedCommand("rm -rf /tmp/demo", "alice", null, "sid-1"))
                .thenReturn("done");

        ApiResponse<String> response = chatController.confirmCommand(request, session);

        assertTrue(response.isSuccess());
        assertEquals("done", response.getData());
        verify(chatService).executeConfirmedCommand("rm -rf /tmp/demo", "alice", null, "sid-1");
        assertFalse(inFlightConfirmKeys().containsKey(key));
    }

    @Test
    void cancelCommand_shouldRecoverWhenInFlightKeyExpired() {
        CancelCommandRequest request = new CancelCommandRequest();
        request.setCommand("rm -rf /tmp/demo");

        String key = "cancel:alice:rm -rf /tmp/demo";
        inFlightConfirmKeys().put(key, currentTimeMs.get() - IN_FLIGHT_TTL_MS - 1L);

        ApiResponse<Void> response = chatController.cancelCommand(request, session);

        assertTrue(response.isSuccess());
        verify(chatService).cancelPendingCommand("alice", null, "rm -rf /tmp/demo");
        assertFalse(inFlightConfirmKeys().containsKey(key));
    }

    @Test
    void cancelCommand_shouldSkipWhenInFlightKeyNotExpired() {
        CancelCommandRequest request = new CancelCommandRequest();
        request.setCommand("rm -rf /tmp/demo");

        String key = "cancel:alice:rm -rf /tmp/demo";
        inFlightConfirmKeys().put(key, currentTimeMs.get() - 2_000L);

        ApiResponse<Void> response = chatController.cancelCommand(request, session);

        assertTrue(response.isSuccess());
        verify(chatService, never()).cancelPendingCommand(anyString(), any(), anyString());
    }

    @Test
    void confirmCommand_shouldAllowOnlyOneConcurrentExecutionPerUserCommand() throws Exception {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("rm -rf /tmp/demo");

        CountDownLatch firstExecutionStarted = new CountDownLatch(1);
        CountDownLatch allowFirstExecutionToFinish = new CountDownLatch(1);

        when(chatService.executeConfirmedCommand("rm -rf /tmp/demo", "alice", null, "sid-1"))
                .thenAnswer(invocation -> {
                    firstExecutionStarted.countDown();
                    if (!allowFirstExecutionToFinish.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("first execution should be released by test");
                    }
                    return "done";
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResponse<String>> firstRequest = executor.submit(() -> chatController.confirmCommand(request, session));
            assertTrue(firstExecutionStarted.await(2, TimeUnit.SECONDS));

            Future<Throwable> secondRequest = executor.submit(() -> {
                try {
                    chatController.confirmCommand(request, session);
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            Throwable secondError = secondRequest.get(2, TimeUnit.SECONDS);
            assertTrue(secondError instanceof IllegalArgumentException);
            assertEquals("此指令正在處理中，請勿重複送出。", secondError.getMessage());

            allowFirstExecutionToFinish.countDown();
            ApiResponse<String> firstResponse = firstRequest.get(2, TimeUnit.SECONDS);
            assertTrue(firstResponse.isSuccess());
            verify(chatService, times(1)).executeConfirmedCommand("rm -rf /tmp/demo", "alice", null, "sid-1");
        } finally {
            allowFirstExecutionToFinish.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void clearHistory_shouldRejectWhenConversationNotOwned() {
        when(conversationService.isConversationOwnedByUser("conv-1", "alice")).thenReturn(false);

        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> chatController.clearHistory("conv-1", session));

        assertEquals("無權存取該對話", exception.getMessage());
        verify(conversationService, never()).clearHistory(anyString(), anyString());
    }

    @Test
    void confirmCommand_shouldRejectWhenConversationNotOwned() {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("rm -rf /tmp/demo");
        request.setConversationId("conv-1");
        when(conversationService.isConversationOwnedByUser("conv-1", "alice")).thenReturn(false);

        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> chatController.confirmCommand(request, session));

        assertEquals("無權存取該對話", exception.getMessage());
        verify(chatService, never()).executeConfirmedCommand(anyString(), anyString(), any(), anyString());
    }

    @Test
    void deleteLastMessages_shouldRejectWhenConversationNotOwned() {
        when(conversationService.isConversationOwnedByUser("conv-1", "alice")).thenReturn(false);

        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> chatController.deleteLastMessages("conv-1", 2, session));

        assertEquals("無權存取該對話", exception.getMessage());
        verify(conversationService, never()).deleteLastMessages(anyString(), anyInt(), anyString());
    }

    @Test
    void getHistory_shouldRejectWhenConversationNotOwned() {
        when(conversationService.isConversationOwnedByUser("conv-1", "alice")).thenReturn(false);

        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> chatController.getHistory("conv-1", 50, 0, session));

        assertEquals("無權存取該對話", exception.getMessage());
        verify(conversationService, never()).getRecentHistory(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void getHistory_cursorPagination_shouldDelegateToCursorService() {
        when(conversationService.isConversationOwnedByUser("conv-1", "alice")).thenReturn(true);
        when(conversationService.getRecentHistoryByCursor(anyString(), anyString(), anyInt(), any(), anyLong()))
                .thenReturn(Map.of("messages", java.util.List.of(), "total", 0L, "limit", 50));

        ApiResponse<Map<String, Object>> response = chatController.getHistory(
                "conv-1",
                50,
                0,
                "2026-03-10T10:00:00",
                123L,
                session
        );

        assertTrue(response.isSuccess());
        verify(conversationService).getRecentHistoryByCursor(
                anyString(),
                anyString(),
                eq(50),
                any(),
                eq(123L)
        );
        verify(conversationService, never()).getRecentHistory(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void getHistory_cursorPagination_missingCursorId_shouldThrow() {
        when(conversationService.isConversationOwnedByUser("conv-1", "alice")).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> chatController.getHistory("conv-1", 50, 0, "2026-03-10T10:00:00", null, session));

        assertEquals("beforeCreatedAt 與 beforeId 必須同時提供", exception.getMessage());
    }

    @Test
    void cancelCommand_shouldRejectWhenConversationNotOwned() {
        CancelCommandRequest request = new CancelCommandRequest();
        request.setCommand("rm -rf /tmp/demo");
        request.setConversationId("conv-1");
        when(conversationService.isConversationOwnedByUser("conv-1", "alice")).thenReturn(false);

        AccessDeniedException exception = assertThrows(
                AccessDeniedException.class,
                () -> chatController.cancelCommand(request, session));

        assertEquals("無權存取該對話", exception.getMessage());
        verify(chatService, never()).cancelPendingCommand(anyString(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> inFlightConfirmKeys() {
        return (Map<String, Long>) ReflectionTestUtils.getField(chatController, "inFlightConfirmKeys");
    }

    private ChatStreamRequest streamRequest() {
        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("hello");
        request.setConversationId(null);
        request.setModel("llama-3.3-70b-versatile");
        return request;
    }

    private Map<String, Object> historyEstimatePage(String... contents) {
        java.util.List<Map<String, Object>> messages = java.util.Arrays.stream(contents)
                .map(content -> Map.<String, Object>of("content", content))
                .toList();
        return Map.of("messages", messages, "total", (long) messages.size(), "limit", 100);
    }

    @SuppressWarnings("unchecked")
    private Map<String, java.util.concurrent.atomic.AtomicInteger> activeStreamsPerUser() {
        return (Map<String, java.util.concurrent.atomic.AtomicInteger>) ReflectionTestUtils.getField(
                chatController,
                "activeStreamsPerUser");
    }
}
