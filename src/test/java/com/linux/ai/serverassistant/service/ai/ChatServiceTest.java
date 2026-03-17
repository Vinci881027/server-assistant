package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.config.AiModelProperties;
import com.linux.ai.serverassistant.dto.ChatStreamRequest;
import com.linux.ai.serverassistant.service.AiModelService;
import com.linux.ai.serverassistant.service.JpaChatMemory;
import com.linux.ai.serverassistant.service.command.CommandConfirmationService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.command.CommandJobService;
import com.linux.ai.serverassistant.service.command.DeterministicRouter;
import com.linux.ai.serverassistant.service.command.OffloadJobService;
import com.linux.ai.serverassistant.service.command.SlashCommandRiskyOperationService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.system.DiskMountService;
import com.linux.ai.serverassistant.service.user.UserManagementService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatServiceTest {

    private ChatService chatService;
    private AiModelService aiModelService;
    private AiStreamRetryCoordinator retryCoordinator;
    private DeterministicRouter deterministicRouter;
    private CommandExecutionService commandExecutionService;
    private CommandConfirmationService commandConfirmationService;
    private JpaChatMemory jpaChatMemory;
    private ConversationService conversationService;
    private UserContext userContext;
    private ConfirmCommandHandler confirmCommandHandler;
    private ToolStatusBus toolStatusBus;

    @BeforeEach
    void setUp() {
        userContext = new UserContext();

        ChatClient.Builder builder = mock(ChatClient.Builder.class, RETURNS_SELF);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        ChatMemory chatMemory = mock(ChatMemory.class);
        jpaChatMemory = mock(JpaChatMemory.class);
        conversationService = mock(ConversationService.class);
        aiModelService = mock(AiModelService.class);
        AiModelProperties aiModelProperties = new AiModelProperties();
        commandExecutionService = mock(CommandExecutionService.class);
        commandConfirmationService = mock(CommandConfirmationService.class);
        deterministicRouter = mock(DeterministicRouter.class);
        retryCoordinator = mock(AiStreamRetryCoordinator.class);
        toolStatusBus = mock(ToolStatusBus.class);
        Map<String, Sinks.Many<String>> toolStatusSinks = new ConcurrentHashMap<>();
        when(toolStatusBus.createSink(anyString())).thenAnswer(invocation -> {
            String contextKey = invocation.getArgument(0, String.class);
            Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer(32, false);
            if (contextKey != null) {
                toolStatusSinks.put(contextKey, sink);
            }
            return sink;
        });
        doAnswer(invocation -> {
            String contextKey = invocation.getArgument(0, String.class);
            if (contextKey == null) {
                return null;
            }
            Sinks.Many<String> sink = toolStatusSinks.remove(contextKey);
            if (sink != null) {
                sink.tryEmitComplete();
            }
            return null;
        }).when(toolStatusBus).complete(anyString());

        DiskMountService diskMountService = mock(DiskMountService.class);
        AdminAuthorizationService adminAuthorizationService = mock(AdminAuthorizationService.class);
        OffloadJobService offloadJobService = mock(OffloadJobService.class);
        CommandJobService commandJobService = mock(CommandJobService.class);
        UserManagementService userManagementService = mock(UserManagementService.class);
        SlashCommandRiskyOperationService riskyOperationService = mock(SlashCommandRiskyOperationService.class);

        confirmCommandHandler = new ConfirmCommandHandler(
                userManagementService,
                commandExecutionService,
                commandConfirmationService,
                riskyOperationService,
                diskMountService,
                adminAuthorizationService,
                offloadJobService,
                commandJobService,
                jpaChatMemory
        );

        aiModelProperties.setDefaultModelKey("20b");
        aiModelProperties.setDefaultModelName("openai/gpt-oss-20b");
        aiModelProperties.setGroqApiKeys(List.of("extra-key-1", "extra-key-2"));
        AiModelProperties.ModelConfig defaultModelConfig = new AiModelProperties.ModelConfig();
        defaultModelConfig.setName("openai/gpt-oss-20b");
        when(aiModelService.getModelsAsMap()).thenReturn(Map.of("20b", defaultModelConfig));
        when(aiModelService.getKeyCooldownMs()).thenReturn(90_000L);
        when(conversationService.resolveConversationIdForStream(any(), anyString())).thenAnswer(inv -> {
            String conversationId = inv.getArgument(0);
            return conversationId == null ? "generated-conv-id" : conversationId;
        });

        chatService = new ChatService(
                builder,
                chatMemory,
                jpaChatMemory,
                conversationService,
                aiModelService,
                aiModelProperties,
                commandExecutionService,
                commandConfirmationService,
                List.of(deterministicRouter),
                confirmCommandHandler,
                userContext,
                retryCoordinator,
                toolStatusBus,
                "primary-key",
                10
        );
    }

    @AfterEach
    void tearDown() {
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
    }

    @Test
    void constructor_blankPrimaryGroqApiKey_shouldFailFast() {
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> new ChatService(
                mock(ChatClient.Builder.class, RETURNS_SELF),
                mock(ChatMemory.class),
                jpaChatMemory,
                conversationService,
                aiModelService,
                new AiModelProperties(),
                commandExecutionService,
                commandConfirmationService,
                List.of(deterministicRouter),
                confirmCommandHandler,
                userContext,
                retryCoordinator,
                toolStatusBus,
                "   ",
                10
        ));

        assertTrue(exception.getMessage().contains("GROQ_API_KEY"));
    }

    @Test
    void streamChat_blankUsername_shouldReturnSecurityError() {
        assertThrows(
                AuthenticationCredentialsNotFoundException.class,
                () -> chatService.streamChat("/gpu", "conv-auth", "20b", " ", "sid-1")
                        .collectList()
                        .block()
        );
    }

    @Test
    void streamChat_messageTooLong_shouldRejectBeforeConversationLookup() {
        String tooLongMessage = "a".repeat(ChatStreamRequest.MAX_MESSAGE_LENGTH + 1);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> chatService.streamChat(tooLongMessage, "conv-1", "20b", "alice", "sid-1")
                        .collectList()
                        .block()
        );

        assertEquals("訊息過長，長度上限為 " + ChatStreamRequest.MAX_MESSAGE_LENGTH + " 字元。", ex.getMessage());
        verify(conversationService, never()).resolveConversationIdForStream(any(), anyString());
        verify(deterministicRouter, never()).route(any());
    }

    @Test
    void streamChat_deterministicAssistantText_shouldBypassAiAndPersistHistory() {
        when(deterministicRouter.route(any()))
                .thenReturn(Optional.of(new DeterministicRouter.AssistantText("GPU 狀態內容")));

        List<String> chunks = chatService.streamChat("/gpu", "conv-1", "20b", "alice", "sid-1")
                .collectList()
                .block();

        assertEquals(List.of("GPU 狀態內容"), chunks);
        verify(conversationService).persistDeterministicExchange("conv-1", "/gpu", "GPU 狀態內容", "alice");
        verify(commandExecutionService, never()).execute(anyString(), any());
    }

    @Test
    void streamChat_cancelKeyword_shouldClearPendingConfirmations() {
        when(deterministicRouter.route(any()))
                .thenReturn(Optional.of(new DeterministicRouter.AssistantText("ok")));

        List<String> chunks = chatService.streamChat("cancel", "conv-cancel", "20b", "alice", "sid-1")
                .collectList()
                .block();

        assertEquals(List.of("ok"), chunks);
        verify(commandConfirmationService).clearAllPendingConfirmations("alice");
    }

    @Test
    void streamChat_nonOwnedConversation_shouldReturnAccessDeniedError() {
        when(conversationService.resolveConversationIdForStream("conv-denied", "alice"))
                .thenThrow(new AccessDeniedException("無權存取該對話"));

        assertThrows(
                AccessDeniedException.class,
                () -> chatService.streamChat("hello", "conv-denied", "20b", "alice", "sid-1")
                        .collectList()
                        .block()
        );
        verify(deterministicRouter, never()).route(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_shouldBudgetHttp429RetriesUsingHealthyGroqKeyCount() {
        when(deterministicRouter.route(any())).thenReturn(Optional.empty());
        when(aiModelService.countHealthyGroqApiKeys()).thenReturn(2);
        when(retryCoordinator.withResilience(
                any(Function.class),
                any(AiStreamRetryCoordinator.EmptyResponseHandler.class),
                any(),
                anyInt()))
                .thenReturn(Flux.just("ok"));

        List<String> chunks = chatService.streamChat("hello", "conv-1", "20b", "alice", "sid-1")
                .collectList()
                .block();

        assertEquals(List.of("ok"), chunks);
        verify(aiModelService).countHealthyGroqApiKeys();
        verify(retryCoordinator).withResilience(
                any(Function.class),
                any(AiStreamRetryCoordinator.EmptyResponseHandler.class),
                any(),
                eq(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_model429_shouldRecordRateLimitForSelectedModel() {
        when(deterministicRouter.route(any())).thenReturn(Optional.empty());
        when(retryCoordinator.withResilience(
                any(Function.class),
                any(AiStreamRetryCoordinator.EmptyResponseHandler.class),
                any(),
                anyInt()))
                .thenReturn(Flux.error(new ModelHttpStatusException(429, new RuntimeException("rate limit"))));
        when(retryCoordinator.resolveHttpStatusCode(any())).thenReturn(429);
        when(retryCoordinator.resolveRateLimitResetDelayMillis(any())).thenReturn(null);
        when(aiModelService.countHealthyGroqApiKeys()).thenReturn(0);

        List<String> chunks = chatService.streamChat("hello", "conv-1", "20b", "alice", "sid-1")
                .collectList()
                .block();

        assertEquals(List.of("[RATE_LIMIT:::90:::0:::]"), chunks);
        verify(aiModelService).recordModelRateLimit("20b");
        verify(aiModelService, atLeastOnce()).countHealthyGroqApiKeys();
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamChat_model429_shouldUseConfigurableDefaultCooldownWhenResetDelayMissing() {
        when(deterministicRouter.route(any())).thenReturn(Optional.empty());
        when(retryCoordinator.withResilience(
                any(Function.class),
                any(AiStreamRetryCoordinator.EmptyResponseHandler.class),
                any(),
                anyInt()))
                .thenReturn(Flux.error(new ModelHttpStatusException(429, new RuntimeException("rate limit"))));
        when(retryCoordinator.resolveHttpStatusCode(any())).thenReturn(429);
        when(retryCoordinator.resolveRateLimitResetDelayMillis(any())).thenReturn(null);
        when(aiModelService.getKeyCooldownMs()).thenReturn(5_000L);

        List<String> chunks = chatService.streamChat("hello", "conv-1", "20b", "alice", "sid-1")
                .collectList()
                .block();

        assertEquals(List.of("[RATE_LIMIT:::5:::0:::]"), chunks);
        verify(aiModelService).recordModelRateLimit("20b");
        verify(aiModelService, atLeastOnce()).countHealthyGroqApiKeys();
    }

    @Test
    void streamChat_moreThanTenConcurrentRequests_shouldQueueAtGlobalSemaphore() throws Exception {
        when(deterministicRouter.route(any())).thenReturn(Optional.empty());
        AtomicInteger activeStreams = new AtomicInteger(0);
        AtomicInteger maxActiveStreams = new AtomicInteger(0);
        // Barrier: 10 streams must arrive before any proceeds, ensuring we
        // observe peak concurrency deterministically (no timing dependency).
        CountDownLatch tenReached = new CountDownLatch(10);
        CountDownLatch canProceed = new CountDownLatch(1);
        when(retryCoordinator.withResilience(
                any(Function.class),
                any(AiStreamRetryCoordinator.EmptyResponseHandler.class),
                any(),
                anyInt()))
                .thenAnswer(invocation -> Flux.defer(() -> {
                    int inFlight = activeStreams.incrementAndGet();
                    maxActiveStreams.getAndUpdate(currentMax -> Math.max(currentMax, inFlight));
                    tenReached.countDown();
                    try {
                        canProceed.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return Flux.just("ok")
                            .doFinally(signal -> activeStreams.decrementAndGet());
                }));

        List<Flux<String>> requests = IntStream.range(0, 12)
                .mapToObj(i -> chatService.streamChat("hello-" + i, "conv-" + i, "20b", "alice", "sid-" + i))
                .toList();

        CompletableFuture<List<String>> future = Flux.merge(requests).collectList().toFuture();

        // Wait until exactly 10 streams are in-flight (semaphore blocks the other 2)
        assertTrue(tenReached.await(10, TimeUnit.SECONDS), "Expected 10 streams to reach the barrier");
        assertTrue(maxActiveStreams.get() <= 10, "Expected max concurrent model streams <= 10");

        // Release all blocked streams; remaining 2 will acquire permits and complete
        canProceed.countDown();
        List<String> chunks = future.get(20, TimeUnit.SECONDS);
        assertEquals(12, chunks.size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleStreamError_model429_shouldRecordRateLimitForSelectedKeyAndModel() throws Exception {
        when(retryCoordinator.resolveHttpStatusCode(any())).thenReturn(429);
        when(retryCoordinator.resolveRateLimitResetDelayMillis(any())).thenReturn(12000L);
        when(aiModelService.countHealthyGroqApiKeys()).thenReturn(0);
        Method handleStreamError = ChatService.class.getDeclaredMethod(
                "handleStreamError", Throwable.class, String.class, String.class, Integer.class);
        handleStreamError.setAccessible(true);

        Flux<String> response = (Flux<String>) handleStreamError.invoke(
                chatService,
                new ModelHttpStatusException(429, new RuntimeException("rate limit")),
                "20b",
                "alice",
                Integer.valueOf(0));

        assertEquals(List.of("[RATE_LIMIT:::12:::0:::]"), response.collectList().block());
        verify(aiModelService).recordKeyRateLimit(0, 12000L);
        verify(aiModelService).recordModelRateLimit("20b");
        verify(aiModelService).countHealthyGroqApiKeys();
    }

    @Test
    @SuppressWarnings("unchecked")
    void handleStreamError_model401_shouldQuarantineSelectedKey() throws Exception {
        when(retryCoordinator.resolveHttpStatusCode(any())).thenReturn(401);
        Method handleStreamError = ChatService.class.getDeclaredMethod(
                "handleStreamError", Throwable.class, String.class, String.class, Integer.class);
        handleStreamError.setAccessible(true);

        Flux<String> response = (Flux<String>) handleStreamError.invoke(
                chatService,
                new ModelHttpStatusException(401, new RuntimeException("unauthorized")),
                "20b",
                "alice",
                Integer.valueOf(0));

        assertEquals(List.of("⚠️ AI 服務授權失敗（401），請聯絡管理員檢查 API 金鑰。"), response.collectList().block());
        verify(aiModelService).recordKeyAuthenticationFailure(0);
        verify(aiModelService, never()).recordModelRateLimit("20b");
    }

    @Test
    void markCurrentKeyRateLimitedOn429Retry_shouldRecordCoolingDownKey() throws Exception {
        Method markCurrentKeyRateLimitedOn429Retry = ChatService.class.getDeclaredMethod(
                "markCurrentKeyRateLimitedOn429Retry", String.class, Integer.class);
        markCurrentKeyRateLimitedOn429Retry.setAccessible(true);

        markCurrentKeyRateLimitedOn429Retry.invoke(chatService, "20b", Integer.valueOf(1));

        verify(aiModelService).recordKeyRateLimit(1, null);
    }

    @Test
    void markCurrentKeyUnavailableOnRetry_http401_shouldRecordAuthenticationFailure() throws Exception {
        when(retryCoordinator.resolveHttpStatusCode(any())).thenReturn(401);
        Method markCurrentKeyUnavailableOnRetry = ChatService.class.getDeclaredMethod(
                "markCurrentKeyUnavailableOnRetry", String.class, Integer.class, Throwable.class);
        markCurrentKeyUnavailableOnRetry.setAccessible(true);

        markCurrentKeyUnavailableOnRetry.invoke(
                chatService,
                "20b",
                Integer.valueOf(1),
                new ModelHttpStatusException(401, new RuntimeException("unauthorized")));

        verify(aiModelService).recordKeyAuthenticationFailure(1);
        verify(aiModelService, never()).recordKeyRateLimit(1, null);
    }

    @Test
    void cancelPendingCommand_withSpecificCommand_shouldClearOnePendingAndReplacePrompt() {
        String cancelText = "❌ 已取消指令：rm -rf /tmp/a\n" +
                CommandMarkers.resolvedCmdMarker("rm -rf /tmp/a", "cancelled");
        when(jpaChatMemory.replaceLatestAssistantCommandPrompt("conv-1", "rm -rf /tmp/a", cancelText))
                .thenReturn(true);

        chatService.cancelPendingCommand("alice", "conv-1", " rm -rf /tmp/a ");

        verify(commandConfirmationService).clearPendingConfirmation("rm -rf /tmp/a", "alice");
        verify(jpaChatMemory).replaceLatestAssistantCommandPrompt("conv-1", "rm -rf /tmp/a", cancelText);
        verify(jpaChatMemory, never()).add(anyString(), any(), anyString());
    }

    @Test
    void cancelPendingCommand_withoutSpecificCommand_shouldClearAllAndAppendCancelText() {
        chatService.cancelPendingCommand("alice", "conv-1", null);

        verify(commandConfirmationService).clearAllPendingConfirmations("alice");
        verify(jpaChatMemory).add(eq("conv-1"), any(), eq("alice"));
    }

    @Test
    void executeConfirmedCommand_nullRawToolResult_shouldReturnFallback() {
        when(commandExecutionService.executeConfirmedCommandWithResult("echo hi", "alice"))
                .thenReturn(new CommandExecutionService.ExecutionResult(null, true, 0));

        String result = chatService.executeConfirmedCommand("echo hi", "alice", null, "sid-1");

        assertEquals("執行完成。", result);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nextGroqApiKey_shouldRotateAcrossConfiguredKeys() throws Exception {
        Method nextGroqApiKey = ChatService.class.getDeclaredMethod("nextGroqApiKey", String.class);
        nextGroqApiKey.setAccessible(true);

        Optional<?> k1 = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");
        Optional<?> k2 = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");
        Optional<?> k3 = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");
        Optional<?> k4 = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");
        Optional<?> k5 = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");

        assertEquals("primary-key", apiKeyOfSelection(k1));
        assertEquals("extra-key-1", apiKeyOfSelection(k2));
        assertEquals("extra-key-2", apiKeyOfSelection(k3));
        assertEquals("primary-key", apiKeyOfSelection(k4));
        assertEquals("extra-key-1", apiKeyOfSelection(k5));
    }

    @Test
    @SuppressWarnings("unchecked")
    void nextGroqApiKey_shouldSkipRecentlyRateLimitedKeys() throws Exception {
        when(aiModelService.isKeyRateLimited(0)).thenReturn(true);
        when(aiModelService.isKeyRateLimited(1)).thenReturn(false);

        Method nextGroqApiKey = ChatService.class.getDeclaredMethod("nextGroqApiKey", String.class);
        nextGroqApiKey.setAccessible(true);

        Optional<?> selected = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");

        assertEquals("extra-key-1", apiKeyOfSelection(selected));
        verify(aiModelService, never()).recordModelRateLimit("20b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nextGroqApiKey_shouldSkipAuthenticationFailedKeys() throws Exception {
        when(aiModelService.isKeyRateLimited(0)).thenReturn(false);
        when(aiModelService.isKeyAuthenticationFailed(0)).thenReturn(true);
        when(aiModelService.isKeyRateLimited(1)).thenReturn(false);
        when(aiModelService.isKeyAuthenticationFailed(1)).thenReturn(false);

        Method nextGroqApiKey = ChatService.class.getDeclaredMethod("nextGroqApiKey", String.class);
        nextGroqApiKey.setAccessible(true);

        Optional<?> selected = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");

        assertEquals("extra-key-1", apiKeyOfSelection(selected));
        verify(aiModelService, never()).recordModelRateLimit("20b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nextGroqApiKey_whenAllKeysRateLimited_shouldMarkModelUnavailable() throws Exception {
        when(aiModelService.isKeyRateLimited(0)).thenReturn(true);
        when(aiModelService.isKeyRateLimited(1)).thenReturn(true);
        when(aiModelService.isKeyRateLimited(2)).thenReturn(true);

        Method nextGroqApiKey = ChatService.class.getDeclaredMethod("nextGroqApiKey", String.class);
        nextGroqApiKey.setAccessible(true);

        Optional<?> selected = (Optional<?>) nextGroqApiKey.invoke(chatService, "20b");

        assertTrue(selected.isEmpty());
        verify(aiModelService).recordModelRateLimit("20b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamModelContent_whenAllKeysRateLimited_shouldShortCircuitWith429() throws Exception {
        when(aiModelService.isKeyRateLimited(0)).thenReturn(true);
        when(aiModelService.isKeyRateLimited(1)).thenReturn(true);
        when(aiModelService.isKeyRateLimited(2)).thenReturn(true);

        Method streamModelContent = ChatService.class.getDeclaredMethod(
                "streamModelContent",
                String.class,
                String.class,
                String.class,
                String.class,
                String.class,
                double.class,
                AtomicReference.class,
                AtomicReference.class);
        streamModelContent.setAccessible(true);

        Flux<String> response = (Flux<String>) streamModelContent.invoke(
                chatService,
                "hello",
                "ctx",
                "20b",
                "openai/gpt-oss-20b",
                "conv-1",
                0.2d,
                new AtomicReference<Integer>(),
                new AtomicReference<Long>());

        ModelHttpStatusException ex =
                assertThrows(ModelHttpStatusException.class, () -> response.collectList().block());
        assertEquals(429, ex.getStatusCode());
        verify(aiModelService).recordModelRateLimit("20b");
    }

    @Test
    void buildOpenAiChatOptions_shouldInjectRotatingAuthorizationHeader() throws Exception {
        Method buildOpenAiChatOptions =
                ChatService.class.getDeclaredMethod(
                        "buildOpenAiChatOptions",
                        String.class,
                        String.class,
                        double.class,
                        AtomicReference.class);
        buildOpenAiChatOptions.setAccessible(true);
        AtomicReference<Integer> keyIndexRef = new AtomicReference<>();

        OpenAiChatOptions o1 = (OpenAiChatOptions) buildOpenAiChatOptions.invoke(
                chatService, "20b", "openai/gpt-oss-20b", 0.2d, keyIndexRef);
        OpenAiChatOptions o2 = (OpenAiChatOptions) buildOpenAiChatOptions.invoke(
                chatService, "20b", "openai/gpt-oss-20b", 0.2d, keyIndexRef);
        OpenAiChatOptions o3 = (OpenAiChatOptions) buildOpenAiChatOptions.invoke(
                chatService, "20b", "openai/gpt-oss-20b", 0.2d, keyIndexRef);

        assertEquals("Bearer primary-key", o1.getHttpHeaders().get("Authorization"));
        assertEquals("Bearer extra-key-1", o2.getHttpHeaders().get("Authorization"));
        assertEquals("Bearer extra-key-2", o3.getHttpHeaders().get("Authorization"));
    }

    private String apiKeyOfSelection(Optional<?> selection) throws Exception {
        assertTrue(selection.isPresent());
        Object value = selection.get();
        Method apiKeyMethod = value.getClass().getDeclaredMethod("apiKey");
        apiKeyMethod.setAccessible(true);
        return (String) apiKeyMethod.invoke(value);
    }

}
