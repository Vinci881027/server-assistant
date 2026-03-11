package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.config.AiModelProperties;
import com.linux.ai.serverassistant.dto.ChatStreamRequest;
import com.linux.ai.serverassistant.service.AiModelService;
import com.linux.ai.serverassistant.service.JpaChatMemory;
import com.linux.ai.serverassistant.service.command.CommandConfirmationService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService.ExecutionOptions;
import com.linux.ai.serverassistant.service.command.DeterministicRouter;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongConsumer;

import static com.linux.ai.serverassistant.util.ToolResultUtils.extractToolResult;

/**
 * Chat Service
 *
 * Coordinates AI chat operations with business logic:
 * - Streaming chat with deterministic routing
 * - Error handling and retry logic
 * - User context management
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String NO_OUTPUT_TEXT = "（無輸出）";
    public static final int STREAM_TIMEOUT_SECONDS = 120;
    private static final java.time.Duration STREAM_TIMEOUT = java.time.Duration.ofSeconds(STREAM_TIMEOUT_SECONDS);
    private static final String STREAM_TIMEOUT_MESSAGE =
            "⚠️ AI 回應超時（" + STREAM_TIMEOUT_SECONDS + " 秒），請稍後再試。";
    private static final int DEFAULT_GLOBAL_AI_STREAM_CONCURRENCY = 10;
    private static final int GLOBAL_AI_STREAM_PERMIT_ACQUIRE_TIMEOUT_SECONDS = 30;
    private static final int MAX_MESSAGE_LENGTH = ChatStreamRequest.MAX_MESSAGE_LENGTH;
    // Keep enough tail to detect marker prefixes split across chunks.
    private static final int COMMAND_MARKER_SCAN_WINDOW = Math.max(
            1,
            Math.max(CommandMarkers.CMD_PREFIX.length(), CommandMarkers.CONFIRM_CMD_PREFIX.length()) - 1);
    private record StreamChunkState(int responseLength, boolean commandMarkerSeen, String commandMarkerTail) {}
    private record ResolvedModelSelection(String key, String name) {}
    private record GroqApiKeySelection(int index, String apiKey) {}

    // ========== Dependencies ==========

    private final ChatClient chatClient;
    private final JpaChatMemory jpaChatMemory;
    private final ConversationService conversationService;
    private final AiModelService aiModelService;
    private final AiModelProperties aiModelProperties;
    private final CommandExecutionService commandExecutionService;
    private final CommandConfirmationService commandConfirmationService;
    private final List<DeterministicRouter> deterministicRouters;
    private final List<DeterministicRouter.ConversationStateCleaner> stateCleaners;
    private final ConfirmCommandHandler confirmCommandHandler;
    private final UserContext userContext;
    private final AiStreamRetryCoordinator retryCoordinator;
    private final List<String> groqApiKeys;
    private final AtomicInteger groqApiKeyCursor = new AtomicInteger(0);
    private final int globalAiStreamConcurrency;
    private final Semaphore globalAiStreamSemaphore;
    private static final String SLASH_COMMAND_UNAVAILABLE_RESPONSE = """
            **指令執行失敗**

            - 此 slash command 暫時無法執行，請稍後再試
            """.trim();
    private static final String SYSTEM_PROMPT = loadSystemPrompt();
    private static final String DYNAMIC_SYSTEM_CONTEXT_TEMPLATE = """
            [當前操作使用者]: %s
            [當前對話ID]: %s
            [當前工具上下文KEY]: %s
            """.strip();

    private static String loadSystemPrompt() {
        try (InputStream is = ChatService.class.getClassLoader().getResourceAsStream("system-prompt.txt")) {
            if (is == null) {
                throw new IllegalStateException("system-prompt.txt not found in classpath");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load system-prompt.txt", e);
        }
    }
    // ========== Constructor ==========

    @Autowired
    public ChatService(
            ChatClient.Builder builder,
            ChatMemory chatMemory,
            JpaChatMemory jpaChatMemory,
            ConversationService conversationService,
            AiModelService aiModelService,
            AiModelProperties aiModelProperties,
            CommandExecutionService commandExecutionService,
            CommandConfirmationService commandConfirmationService,
            List<DeterministicRouter> deterministicRouters,
            ConfirmCommandHandler confirmCommandHandler,
            UserContext userContext,
            AiStreamRetryCoordinator retryCoordinator,
            @Value("${spring.ai.openai.api-key:}") String primaryGroqApiKey,
            @Value("${app.security.chat.global-concurrent-ai-streams:10}") int globalAiStreamConcurrency) {
        this.jpaChatMemory = jpaChatMemory;
        this.conversationService = conversationService;
        this.aiModelService = aiModelService;
        this.aiModelProperties = aiModelProperties;
        this.commandExecutionService = commandExecutionService;
        this.commandConfirmationService = commandConfirmationService;
        this.deterministicRouters = deterministicRouters;
        this.stateCleaners = deterministicRouters.stream()
                .filter(r -> r instanceof DeterministicRouter.ConversationStateCleaner)
                .map(r -> (DeterministicRouter.ConversationStateCleaner) r)
                .toList();
        this.confirmCommandHandler = confirmCommandHandler;
        this.userContext = userContext;
        this.retryCoordinator = retryCoordinator;
        validatePrimaryGroqApiKey(primaryGroqApiKey);
        this.globalAiStreamConcurrency = sanitizeGlobalAiStreamConcurrency(globalAiStreamConcurrency);
        this.globalAiStreamSemaphore = new Semaphore(this.globalAiStreamConcurrency, true);
        this.groqApiKeys = resolveGroqApiKeys(primaryGroqApiKey, aiModelProperties.getGroqApiKeys());
        this.aiModelService.configureGroqApiKeyCount(this.groqApiKeys.size());
        if (this.groqApiKeys.size() > 1) {
            log.info("Groq API key rotation enabled with {} keys.", this.groqApiKeys.size());
        }

        // Spring AI 1.1.0 MessageChatMemoryAdvisor always reads full conversation history into the prompt.
        // Wrap memory as write-only so history is still persisted, but never re-injected into model input.
        ChatMemory writeOnlyMemory = createWriteOnlyMemory(chatMemory);

        // Build ChatClient with all default tools and memory advisor (stores history for sidebar display)
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolNames(
                        "executeLinuxCommand",
                        "listDirectory", "readFileContent", "writeFileContent", "createDirectory",
                        "manageSshKeys", "manageUsers"
                )
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(writeOnlyMemory).build())
                .build();
    }

    private void validatePrimaryGroqApiKey(String primaryGroqApiKey) {
        if (!StringUtils.hasText(primaryGroqApiKey)) {
            throw new IllegalStateException("GROQ_API_KEY 未設定（spring.ai.openai.api-key 不可為空）。");
        }
    }

    private int sanitizeGlobalAiStreamConcurrency(int configuredConcurrency) {
        if (configuredConcurrency > 0) {
            return configuredConcurrency;
        }
        log.warn("Invalid app.security.chat.global-concurrent-ai-streams={} ; fallback to {}",
                configuredConcurrency,
                DEFAULT_GLOBAL_AI_STREAM_CONCURRENCY);
        return DEFAULT_GLOBAL_AI_STREAM_CONCURRENCY;
    }

    // ========== Public API - Streaming Chat ==========

    /**
     * Handles streaming chat.
     *
     * @param message user's message
     * @param conversationId conversation ID (generated if null)
     * @param model model key (e.g., "20b", "120b")
     * @param username authenticated username (required, non-blank)
     * @param sessionId HTTP session ID for credential resolution on tool threads
     * @return Flux stream of response chunks
     */
    public Flux<String> streamChat(
            String message,
            String conversationId,
            String model,
            String username,
            String sessionId) {
        return streamChat(message, conversationId, model, username, sessionId, ignored -> { });
    }

    public Flux<String> streamChat(
            String message,
            String conversationId,
            String model,
            String username,
            String sessionId,
            LongConsumer totalTokenUsageConsumer) {
        if (message == null || message.isBlank()) {
            return Flux.error(new IllegalArgumentException("訊息不可為空。"));
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return Flux.error(new IllegalArgumentException("訊息過長，長度上限為 " + MAX_MESSAGE_LENGTH + " 字元。"));
        }
        if (username == null || username.isBlank()) {
            return Flux.error(new AuthenticationCredentialsNotFoundException("未登入或 Session 已失效，請重新登入。"));
        }

        ResolvedModelSelection modelSelection = resolveModelSelection(model);
        if (modelSelection.name() == null || modelSelection.name().isBlank()) {
            return Flux.error(new IllegalStateException("找不到可用的 AI 模型設定，請聯絡管理員。"));
        }

        String currentConversationId;
        try {
            currentConversationId = conversationService.resolveConversationIdForStream(conversationId, username);
        } catch (AccessDeniedException ex) {
            log.warn("SECURITY: user '{}' attempted to access conversation '{}' without permission", username, conversationId);
            return Flux.error(ex);
        }

        return Flux.defer(() -> streamChatWithRegisteredContext(
                message,
                currentConversationId,
                modelSelection.key(),
                modelSelection.name(),
                username,
                sessionId,
                resolveUsageConsumer(totalTokenUsageConsumer)));
    }

    // ========== Public API - Direct Command Cancellation ==========

    /**
     * Cancels a pending command directly, bypassing the AI model entirely.
     *
     * Called when the user clicks the cancel button in the frontend.
     * This avoids sending "取消操作" through the AI model which can leave confusing
     * history that causes the next deletion attempt to fail with empty response.
     *
     * @param username the authenticated user
     * @param conversationId conversation ID for memory update
     */
    public void cancelPendingCommand(String username, String conversationId, String command) {
        String trimmedCommand = command == null ? null : command.trim();
        boolean hasCommand = trimmedCommand != null && !trimmedCommand.isBlank();

        if (hasCommand) {
            commandConfirmationService.clearPendingConfirmation(trimmedCommand, username);
        } else {
            commandConfirmationService.clearAllPendingConfirmations(username);
        }
        for (DeterministicRouter.ConversationStateCleaner cleaner : stateCleaners) {
            try {
                cleaner.clearConversationState(conversationId, username);
            } catch (Exception ex) {
                log.warn("Failed to clear router state for '{}': {}",
                        cleaner.getClass().getSimpleName(), ex.getMessage());
            }
        }

        if (conversationId != null && !conversationId.isBlank()) {
            String cancelText = hasCommand
                    ? "❌ 已取消指令：" + trimmedCommand + "\n"
                        + CommandMarkers.resolvedCmdMarker(trimmedCommand, "cancelled")
                    : "❌ 已取消操作。";
            boolean replaced = hasCommand &&
                    jpaChatMemory.replaceLatestAssistantCommandPrompt(conversationId, trimmedCommand, cancelText);
            if (!replaced) {
                jpaChatMemory.add(conversationId, List.of(new AssistantMessage(cancelText)), username);
            }
        }
    }

    // ========== Public API - Direct Command Confirmation ==========

    /**
     * Executes a confirmed command directly, bypassing the AI model entirely.
     * Called when the user clicks the confirmation button in the frontend.
     * Commands are validated against pending-confirmation records before execution.
     */
    @Transactional
    public String executeConfirmedCommand(String command, String username, String conversationId, String sessionId) {
        if (command == null || command.isBlank()) {
            return CommandMarkers.SECURITY_VIOLATION + " 命令不能為空。";
        }

        String trimmed = command.trim();
        ConfirmCommandHandler.HandlerResult handlerResult =
                confirmCommandHandler.handle(trimmed, username, conversationId, sessionId);
        if (handlerResult.immediateResponse() != null) {
            return handlerResult.immediateResponse();
        }

        String rawToolResult = handlerResult.rawToolResult();
        String result = rawToolResult == null
                ? "執行完成。"
                : extractToolResult(rawToolResult, "執行完成。");
        boolean executionFailed = handlerResult.executionFailed();
        String statusText = executionFailed ? "❌ 操作失敗" : "✅ 操作完成";
        if (executionFailed && handlerResult.exitCode() != null) {
            statusText = statusText + "（Exit Code: " + handlerResult.exitCode() + "）";
        }
        String memoryText = statusText + "：" + result + "\n"
                + CommandMarkers.resolvedCmdMarker(handlerResult.commandForMemory(), "confirmed");

        // Replace the [CMD:::] confirmation prompt with the actual result.
        // Using replaceLastAssistantMessage prevents consecutive AssistantMessages
        if (conversationId != null && !conversationId.isBlank()) {
            boolean replaced = jpaChatMemory.replaceLatestAssistantCommandPrompt(
                    conversationId, handlerResult.commandForMemory(), memoryText);
            if (!replaced) {
                jpaChatMemory.replaceLastAssistantMessage(conversationId, memoryText, username);
            }
        }

        return result;
    }

    private Flux<String> streamChatWithRegisteredContext(
            String message,
            String conversationId,
            String modelKey,
            String modelName,
            String username,
            String sessionId,
            LongConsumer totalTokenUsageConsumer) {
        String toolContextKey = UUID.randomUUID().toString();
        String conversationBindingKey =
                registerUserExecutionContext(toolContextKey, conversationId, username, sessionId);
        try {
            Flux<String> response = buildStreamChatResponse(
                    message,
                    conversationId,
                    modelKey,
                    modelName,
                    username,
                    toolContextKey,
                    totalTokenUsageConsumer);
            return response.doFinally(signal -> clearUserExecutionContext(toolContextKey, conversationBindingKey));
        } catch (Throwable t) {
            clearUserExecutionContext(toolContextKey, conversationBindingKey);
            return Flux.error(t);
        }
    }

    private Flux<String> buildStreamChatResponse(
            String message,
            String conversationId,
            String modelKey,
            String modelName,
            String username,
            String toolContextKey,
            LongConsumer totalTokenUsageConsumer) {
        clearPendingConfirmationsIfExplicitCancel(message, username);

        Optional<Flux<String>> deterministicRoute = tryDeterministicRouting(
                message,
                conversationId,
                username,
                toolContextKey);
        if (deterministicRoute.isPresent()) {
            return deterministicRoute.get();
        }

        return streamAiResponse(message, conversationId, modelKey, modelName, username, toolContextKey, totalTokenUsageConsumer);
    }

    private void clearPendingConfirmationsIfExplicitCancel(String message, String username) {
        if (CommandMarkers.isCancelIntent(message)) {
            commandConfirmationService.clearAllPendingConfirmations(username);
        }
    }

    private Optional<Flux<String>> tryDeterministicRouting(
            String message,
            String conversationId,
            String username,
            String toolContextKey) {
        boolean slashCommand = isSlashCommand(message);
        DeterministicRouter.Context routeCtx =
                new DeterministicRouter.Context(message, conversationId, username);

        for (DeterministicRouter router : deterministicRouters) {
            Optional<DeterministicRouter.Route> route;
            try {
                route = router.route(routeCtx);
            } catch (Exception ex) {
                log.warn("Deterministic router '{}' failed: {}",
                        router.getClass().getSimpleName(),
                        ex.getMessage());
                if (slashCommand && router instanceof DeterministicRouter.SlashCommandAware) {
                    return Optional.of(respondWithDeterministicRoute(
                            conversationId,
                            message,
                            SLASH_COMMAND_UNAVAILABLE_RESPONSE,
                            username));
                }
                continue;
            }

            if (route.isPresent()) {
                Optional<Flux<String>> handledRoute = handleDeterministicRoute(
                        route.get(),
                        router,
                        slashCommand,
                        message,
                        conversationId,
                        username,
                        toolContextKey);
                if (handledRoute.isPresent()) {
                    return handledRoute;
                }
                log.warn("Deterministic router '{}' returned unsupported route type '{}'; fallback to AI.",
                        router.getClass().getSimpleName(),
                        route.get().getClass().getName());
                break;
            }
        }

        return Optional.empty();
    }

    private boolean isSlashCommand(String message) {
        return message != null && message.trim().startsWith("/");
    }

    private Optional<Flux<String>> handleDeterministicRoute(
            DeterministicRouter.Route route,
            DeterministicRouter router,
            boolean slashCommand,
            String message,
            String conversationId,
            String username,
            String toolContextKey) {
        if (route instanceof DeterministicRouter.AssistantText textRoute) {
            return Optional.of(respondWithDeterministicRoute(conversationId, message, textRoute.text(), username));
        }
        if (route instanceof DeterministicRouter.LinuxCommand commandRoute) {
            return Optional.of(handleDeterministicLinuxCommandRoute(
                    commandRoute,
                    router,
                    slashCommand,
                    message,
                    conversationId,
                    username,
                    toolContextKey));
        }
        return Optional.empty();
    }

    private Flux<String> handleDeterministicLinuxCommandRoute(
            DeterministicRouter.LinuxCommand commandRoute,
            DeterministicRouter router,
            boolean slashCommand,
            String message,
            String conversationId,
            String username,
            String toolContextKey) {
        boolean noAuditPath = slashCommand || router instanceof DeterministicRouter.NoAuditLinuxCommandRouter;
        ExecutionOptions.Builder optionsBuilder = ExecutionOptions.builder()
                .confirm(commandRoute.confirm())
                .user(username)
                .noOptimize();
        if (noAuditPath) {
            optionsBuilder.noAudit();
        }
        ExecutionOptions options = optionsBuilder.build();
        String raw = userContext.withContextKey(
                toolContextKey,
                () -> commandExecutionService.execute(commandRoute.command(), options));
        String formattedResult = formatDeterministicToolResult(message, commandRoute.command(), raw);
        String prefix = commandRoute.responsePrefix() != null ? commandRoute.responsePrefix() : "";
        String assistantResponse = prefix + (formattedResult.isBlank() ? NO_OUTPUT_TEXT : formattedResult);
        return respondWithDeterministicRoute(conversationId, message, assistantResponse, username);
    }

    private String formatDeterministicToolResult(String message, String command, String rawToolResult) {
        String toolResult = extractToolResult(rawToolResult, "執行完成。");
        toolResult = ResponseFormattingUtils.maybeRewriteExclamationCommandNotFound(message, command, toolResult);
        toolResult = ResponseFormattingUtils.maybeFormatDiskUsageAsMarkdownTable(command, toolResult);
        return ResponseFormattingUtils.maybeFormatTerminalOutputAsCodeBlock(toolResult);
    }

    private Flux<String> streamAiResponse(
            String message,
            String conversationId,
            String modelKey,
            String modelName,
            String username,
            String toolContextKey,
            LongConsumer totalTokenUsageConsumer) {
        AtomicReference<StreamChunkState> streamChunkState = new AtomicReference<>(
                new StreamChunkState(0, false, ""));
        AtomicReference<Integer> selectedGroqApiKeyIndex = new AtomicReference<>(null);
        AtomicReference<Long> totalUsageTokens = new AtomicReference<>(null);
        String dynamicSystemContext = buildDynamicSystemContext(username, conversationId, toolContextKey);

        Flux<String> modelStream = streamModelContentWithResilience(
                message,
                dynamicSystemContext,
                modelKey,
                modelName,
                conversationId,
                username,
                selectedGroqApiKeyIndex,
                totalUsageTokens);

        Flux<String> response = withPendingPromptFallbackIfNoMarker(
                trackStreamResponse(modelStream, streamChunkState),
                username,
                streamChunkState)
                .timeout(STREAM_TIMEOUT, Flux.just(STREAM_TIMEOUT_MESSAGE))
                .doOnCancel(() -> log.debug("用戶中斷連線"))
                .doOnComplete(() -> onAiResponseCompleted(
                        streamChunkState.get().responseLength(), modelName, conversationId, username))
                .onErrorResume(e -> handleStreamError(e, modelKey, username, selectedGroqApiKeyIndex.get()))
                .doFinally(signalType -> reportResolvedTotalUsage(
                        resolveUsageConsumer(totalTokenUsageConsumer),
                        totalUsageTokens.get(),
                        signalType,
                        modelKey,
                        username));
        return withGlobalAiStreamPermit(response, username, conversationId);
    }

    private Flux<String> withGlobalAiStreamPermit(Flux<String> source, String username, String conversationId) {
        return Mono.fromCallable(() -> {
                    acquireGlobalAiStreamPermit(username, conversationId);
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(ignored -> source.doFinally(signalType ->
                        releaseGlobalAiStreamPermit(signalType, username, conversationId)));
    }

    private void acquireGlobalAiStreamPermit(String username, String conversationId) {
        try {
            boolean acquired = globalAiStreamSemaphore.tryAcquire(
                    GLOBAL_AI_STREAM_PERMIT_ACQUIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("Global AI stream permit acquire timed out after {}s: username={}, conversationId={}",
                        GLOBAL_AI_STREAM_PERMIT_ACQUIRE_TIMEOUT_SECONDS,
                        username,
                        conversationId);
                throw new GlobalAiStreamPermitTimeoutException("系統繁忙，請稍後再試。");
            }
            int inFlight = globalAiStreamConcurrency - globalAiStreamSemaphore.availablePermits();
            log.debug("Acquired global AI stream permit: inFlight={}, username={}, conversationId={}",
                    inFlight,
                    username,
                    conversationId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待全局 AI 串流併發配額時被中斷", e);
        }
    }

    private void releaseGlobalAiStreamPermit(SignalType signalType, String username, String conversationId) {
        globalAiStreamSemaphore.release();
        int inFlight = globalAiStreamConcurrency - globalAiStreamSemaphore.availablePermits();
        log.debug("Released global AI stream permit: inFlight={}, signal={}, username={}, conversationId={}",
                inFlight,
                signalType,
                username,
                conversationId);
    }

    private Flux<String> trackStreamResponse(
            Flux<String> source,
            AtomicReference<StreamChunkState> streamChunkState) {
        return source.doOnNext(chunk -> trackResponseChunk(chunk, streamChunkState));
    }

    private void trackResponseChunk(
            String chunk,
            AtomicReference<StreamChunkState> streamChunkState) {
        if (chunk == null) {
            return;
        }
        streamChunkState.updateAndGet(currentState -> {
            String combined = currentState.commandMarkerTail() + chunk;
            boolean nextCommandMarkerSeen = currentState.commandMarkerSeen()
                    || CommandMarkers.containsCommandMarker(combined);
            return new StreamChunkState(
                    currentState.responseLength() + chunk.length(),
                    nextCommandMarkerSeen,
                    retainTail(combined, COMMAND_MARKER_SCAN_WINDOW));
        });
    }

    private Flux<String> withPendingPromptFallbackIfNoMarker(
            Flux<String> source,
            String username,
            AtomicReference<StreamChunkState> streamChunkState) {
        return source.concatWith(Flux.defer(() -> {
            StreamChunkState currentState = streamChunkState.get();
            log.debug("concatWith fired: cmdTagSeen={}, responseLength={}",
                    currentState.commandMarkerSeen(), currentState.responseLength());
            if (currentState.commandMarkerSeen()) {
                return Flux.empty();
            }
            Optional<String> prompt = commandConfirmationService.getPendingCommandPrompt(username);
            log.debug("concatWith getPendingCommandPrompt result: {}", prompt);
            return prompt.map(Flux::just).orElseGet(Flux::empty);
        }));
    }

    private String retainTail(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(text.length() - maxLength);
    }

    private void onAiResponseCompleted(int responseLength, String modelName, String conversationId, String username) {
        if (responseLength == 0) {
            log.warn("AI Stream completed with 0 length. Model: {}", modelName);
        }
        conversationService.assignUsernameToConversation(conversationId, username);
    }

    private ResolvedModelSelection resolveModelSelection(String requestedModel) {
        Map<String, AiModelProperties.ModelConfig> models = aiModelService.getModelsAsMap();
        String selectedModelKey = requestedModel;
        AiModelProperties.ModelConfig modelConfig = models.get(selectedModelKey);
        if (modelConfig == null) {
            selectedModelKey = aiModelProperties.getDefaultModelKey();
            modelConfig = models.get(selectedModelKey);
        }
        if (modelConfig == null && !models.isEmpty()) {
            Map.Entry<String, AiModelProperties.ModelConfig> first = models.entrySet().iterator().next();
            selectedModelKey = first.getKey();
            modelConfig = first.getValue();
        }

        String selectedModelName = modelConfig == null ? null : modelConfig.getName();
        if (selectedModelName != null && !selectedModelName.isBlank()) {
            return new ResolvedModelSelection(selectedModelKey, selectedModelName);
        }

        String configuredDefaultModelName = aiModelProperties.getDefaultModelName();
        if (configuredDefaultModelName != null && !configuredDefaultModelName.isBlank()) {
            return new ResolvedModelSelection(selectedModelKey, configuredDefaultModelName);
        }

        String fallbackName = models.values().stream()
                .map(AiModelProperties.ModelConfig::getName)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
        return new ResolvedModelSelection(selectedModelKey, fallbackName);
    }

    private ChatMemory createWriteOnlyMemory(ChatMemory delegate) {
        return new WriteOnlyChatMemory(delegate);
    }

    /**
     * A {@link ChatMemory} decorator that persists messages normally but always returns an
     * empty history on {@link #get}. This prevents the {@link MessageChatMemoryAdvisor}
     * from injecting the full conversation history into the prompt — useful when history
     * has already been manually included in the system context, or when we want the model
     * to operate without prior context (e.g. confirmation sub-flows).
     */
    private static final class WriteOnlyChatMemory implements ChatMemory {
        private final ChatMemory delegate;

        WriteOnlyChatMemory(ChatMemory delegate) {
            this.delegate = delegate;
        }

        @Override
        public void add(String conversationId, List<Message> messages) {
            delegate.add(conversationId, messages);
        }

        @Override
        public List<Message> get(String conversationId) {
            return List.of();
        }

        @Override
        public void clear(String conversationId) {
            delegate.clear(conversationId);
        }
    }

    // ========== Private Helper Methods ==========

    private Flux<String> respondWithDeterministicRoute(
            String conversationId, String userMessage, String assistantResponse, String username) {
        conversationService.persistDeterministicExchange(
                conversationId,
                userMessage,
                assistantResponse,
                username);
        return Flux.just(assistantResponse);
    }

    /**
     * Handles errors during streaming.
     *
     * @param e the exception
     * @param username the username
     * @return Flux with error message
     */
    private Flux<String> handleStreamError(Throwable e, String modelKey, String username, Integer keyIndex) {
        log.error("AI Stream Failed: {}", e.getMessage(), e);
        commandConfirmationService.clearUserPendingConfirmations(username);
        if (e instanceof GlobalAiStreamPermitTimeoutException) {
            return Flux.just("⚠️ 系統繁忙，目前同時使用人數較多，請稍後再試。");
        }

        final int HTTP_TOO_MANY_REQUESTS = 429;
        final int HTTP_UNAUTHORIZED = 401;
        final int HTTP_FORBIDDEN = 403;
        final int HTTP_SERVICE_UNAVAILABLE = 503;
        final int HTTP_PAYLOAD_TOO_LARGE = 413;

        Integer httpStatus = retryCoordinator.resolveHttpStatusCode(e);
        if (httpStatus != null) {
            if (httpStatus == HTTP_TOO_MANY_REQUESTS) {
                Long cooldownMs = retryCoordinator.resolveRateLimitResetDelayMillis(e);
                if (keyIndex != null) {
                    aiModelService.recordKeyRateLimit(keyIndex, cooldownMs);
                }
                aiModelService.recordModelRateLimit(modelKey);
                long retryAfterSeconds = resolveRateLimitRetryAfterSeconds(cooldownMs);
                Integer remainingKeyCount = groqApiKeys.isEmpty() ? null : aiModelService.countHealthyGroqApiKeys();
                return Flux.just(buildRateLimitMarker(retryAfterSeconds, remainingKeyCount));
            }
            if (httpStatus == HTTP_UNAUTHORIZED || httpStatus == HTTP_FORBIDDEN) {
                markCurrentKeyAuthenticationFailed(modelKey, keyIndex, httpStatus, false);
                return Flux.just("⚠️ AI 服務授權失敗（" + httpStatus + "），請聯絡管理員檢查 API 金鑰。");
            }
            if (httpStatus == HTTP_SERVICE_UNAVAILABLE) {
                return Flux.just("⚠️ AI 服務暫時無法使用（503）。Groq 伺服器目前過載，已自動重試但仍失敗，請稍後再試。");
            }
            if (httpStatus == HTTP_PAYLOAD_TOO_LARGE) {
                return Flux.just("⚠️ 請求內容過大 (413 Payload Too Large)。請清除該對話歷史後重試。");
            }
        }

        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase(Locale.ROOT).contains("payload too large")) {
            return Flux.just("⚠️ 請求內容過大 (413 Payload Too Large)。請清除該對話歷史後重試。");
        }
        log.error("Unhandled stream error", e);
        return Flux.just("⚠️ 系統發生錯誤，請稍後再試。");
    }

    private Flux<String> streamModelContentWithResilience(
            String message,
            String dynamicSystemContext,
            String modelKey,
            String modelName,
            String conversationId,
            String username,
            AtomicReference<Integer> selectedGroqApiKeyIndex,
            AtomicReference<Long> totalUsageTokens) {
        int maxKeyFailoverRetries = resolveMaxKeyFailoverRetries();
        return retryCoordinator.withResilience(
                temperature -> streamModelContent(
                        message,
                        dynamicSystemContext,
                        modelKey,
                        modelName,
                        conversationId,
                        temperature,
                        selectedGroqApiKeyIndex,
                        totalUsageTokens),
                attempt -> handleEmptyModelResponse(conversationId, username, attempt),
                failure -> markCurrentKeyUnavailableOnRetry(
                        modelKey,
                        selectedGroqApiKeyIndex.get(),
                        failure),
                maxKeyFailoverRetries);
    }

    private int resolveMaxKeyFailoverRetries() {
        if (groqApiKeys.isEmpty()) {
            return 0;
        }
        return Math.max(0, aiModelService.countHealthyGroqApiKeys() - 1);
    }

    private void markCurrentKeyUnavailableOnRetry(String modelKey, Integer keyIndex, Throwable failure) {
        Integer httpStatus = retryCoordinator.resolveHttpStatusCode(failure);
        if (httpStatus == null) {
            return;
        }
        if (httpStatus == 429) {
            markCurrentKeyRateLimitedOn429Retry(
                    modelKey,
                    keyIndex,
                    retryCoordinator.resolveRateLimitResetDelayMillis(failure));
            return;
        }
        if (httpStatus == 401 || httpStatus == 403) {
            markCurrentKeyAuthenticationFailed(modelKey, keyIndex, httpStatus, true);
        }
    }

    private void markCurrentKeyRateLimitedOn429Retry(String modelKey, Integer keyIndex) {
        markCurrentKeyRateLimitedOn429Retry(modelKey, keyIndex, null);
    }

    private void markCurrentKeyRateLimitedOn429Retry(String modelKey, Integer keyIndex, Long cooldownMs) {
        if (keyIndex == null) {
            return;
        }
        aiModelService.recordKeyRateLimit(keyIndex, cooldownMs);
        log.warn("[KEY_FAILOVER] model={} keyIndex={} marked cooling-down after 429; cooldownMs={} retrying with next key",
                modelKey, keyIndex, cooldownMs);
    }

    private void markCurrentKeyAuthenticationFailed(
            String modelKey,
            Integer keyIndex,
            Integer httpStatus,
            boolean retryingWithNextKey) {
        if (keyIndex == null || httpStatus == null || (httpStatus != 401 && httpStatus != 403)) {
            return;
        }
        aiModelService.recordKeyAuthenticationFailure(keyIndex);
        if (retryingWithNextKey) {
            log.warn("[KEY_FAILOVER] model={} keyIndex={} quarantined after auth failure {}; retrying with next key",
                    modelKey,
                    keyIndex,
                    httpStatus);
            return;
        }
        log.warn("[KEY_FAILOVER] model={} keyIndex={} quarantined after auth failure {}; no retry budget left",
                modelKey,
                keyIndex,
                httpStatus);
    }

    private long resolveRateLimitRetryAfterSeconds(Long cooldownMs) {
        long defaultCooldownMs = aiModelService.getKeyCooldownMs();
        long effectiveCooldownMs = cooldownMs == null ? defaultCooldownMs : Math.max(0L, cooldownMs);
        long retryAfterSeconds = (effectiveCooldownMs + 999L) / 1_000L;
        return Math.max(1L, retryAfterSeconds);
    }

    private String buildRateLimitMarker(long retryAfterSeconds, Integer remainingKeyCount) {
        if (remainingKeyCount == null) {
            return "[RATE_LIMIT:::" + retryAfterSeconds + ":::]";
        }
        return "[RATE_LIMIT:::" + retryAfterSeconds + ":::" + Math.max(0, remainingKeyCount) + ":::]";
    }

    private static final class GlobalAiStreamPermitTimeoutException extends RuntimeException {
        private GlobalAiStreamPermitTimeoutException(String message) {
            super(message);
        }
    }

    private Flux<String> handleEmptyModelResponse(String conversationId, String username, int emptyRetryCount) {
        log.debug("[EMPTY_RETRY] empty content (attempt={}, username={})", emptyRetryCount + 1, username);
        cleanupEmptyExchange(conversationId, username);

        Optional<String> pending = commandConfirmationService.getPendingCommandPrompt(username);
        if (pending.isPresent()) {
            log.debug("[EMPTY_RETRY] returning pending confirmation prompt");
            return Flux.just(pending.get());
        }

        return Flux.error(new EmptyModelResponseException());
    }

    private Flux<String> streamModelContent(
            String message,
            String dynamicSystemContext,
            String modelKey,
            String modelName,
            String conversationId,
            double temperature,
            AtomicReference<Integer> selectedGroqApiKeyIndex,
            AtomicReference<Long> totalUsageTokens) {
        OpenAiChatOptions options = buildOpenAiChatOptions(modelKey, modelName, temperature, selectedGroqApiKeyIndex);
        if (!groqApiKeys.isEmpty() && selectedGroqApiKeyIndex.get() == null) {
            // No healthy API key could be selected; no upstream model request was made.
            totalUsageTokens.compareAndSet(null, 0L);
            return Flux.error(new ModelHttpStatusException(
                    429,
                    new IllegalStateException("All Groq API keys are temporarily rate-limited"),
                    null,
                    false));
        }
        return chatClient.prompt()
                .messages(new SystemMessage(dynamicSystemContext))
                .user(message)
                .options(options)
                .advisors(a -> {
                    a.param("chat_memory_conversation_id", conversationId);
                    a.param("chat_memory_retrieve_size", 0);
                })
                .stream()
                .chatResponse()
                .doOnNext(chatResponse -> captureUsageTotalTokens(chatResponse, totalUsageTokens))
                .map(chatResponse -> Optional.ofNullable(chatResponse)
                        .map(ChatResponse::getResult)
                        .map(result -> result.getOutput())
                        .map(output -> output.getText())
                        .orElse(""))
                .filter(text -> text != null && !text.isEmpty());
    }

    private OpenAiChatOptions buildOpenAiChatOptions(
            String modelKey,
            String modelName,
            double temperature,
            AtomicReference<Integer> selectedGroqApiKeyIndex) {
        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .model(modelName)
                .temperature(temperature)
                .streamUsage(true);
        selectedGroqApiKeyIndex.set(null);
        nextGroqApiKey(modelKey).ifPresent(selection -> {
            selectedGroqApiKeyIndex.set(selection.index());
            optionsBuilder.httpHeaders(Map.of("Authorization", "Bearer " + selection.apiKey()));
        });
        return optionsBuilder.build();
    }

    private void captureUsageTotalTokens(ChatResponse chatResponse, AtomicReference<Long> totalUsageTokens) {
        if (chatResponse == null || chatResponse.getMetadata() == null || chatResponse.getMetadata().getUsage() == null) {
            return;
        }
        Integer totalTokens = chatResponse.getMetadata().getUsage().getTotalTokens();
        if (totalTokens == null || totalTokens <= 0) {
            return;
        }
        long resolvedTokens = totalTokens.longValue();
        totalUsageTokens.updateAndGet(previous -> previous == null ? resolvedTokens : Math.max(previous, resolvedTokens));
    }

    private void reportResolvedTotalUsage(
            LongConsumer totalTokenUsageConsumer,
            Long totalUsageTokens,
            SignalType signalType,
            String modelKey,
            String username) {
        if (totalUsageTokens == null || totalUsageTokens <= 0L) {
            log.debug("No AI usage metadata received for stream: model={}, user={}, signal={}", modelKey, username, signalType);
            return;
        }
        try {
            totalTokenUsageConsumer.accept(totalUsageTokens);
        } catch (Exception ex) {
            log.warn("Failed to report resolved AI usage (model={}, user={}): {}", modelKey, username, ex.getMessage());
        }
    }

    private LongConsumer resolveUsageConsumer(LongConsumer usageConsumer) {
        return usageConsumer != null ? usageConsumer : ignored -> { };
    }

    private Optional<GroqApiKeySelection> nextGroqApiKey(String modelKey) {
        if (groqApiKeys.isEmpty()) {
            return Optional.empty();
        }
        int keyCount = groqApiKeys.size();
        for (int attempts = 0; attempts < keyCount; attempts++) {
            int index = Math.floorMod(groqApiKeyCursor.getAndIncrement(), keyCount);
            if (!aiModelService.isKeyRateLimited(index) && !aiModelService.isKeyAuthenticationFailed(index)) {
                return Optional.of(new GroqApiKeySelection(index, groqApiKeys.get(index)));
            }
        }
        aiModelService.recordModelRateLimit(modelKey);
        return Optional.empty();
    }

    private List<String> resolveGroqApiKeys(String primaryGroqApiKey, List<String> additionalGroqApiKeys) {
        Set<String> keyPool = new LinkedHashSet<>();
        addGroqKeys(keyPool, primaryGroqApiKey);
        if (additionalGroqApiKeys != null) {
            additionalGroqApiKeys.forEach(key -> addGroqKeys(keyPool, key));
        }
        return List.copyOf(keyPool);
    }

    private void addGroqKeys(Set<String> keyPool, String rawValue) {
        if (rawValue == null) {
            return;
        }
        for (String token : rawValue.split(",")) {
            String key = token.trim();
            if (!key.isEmpty()) {
                keyPool.add(key);
            }
        }
    }

    private void cleanupEmptyExchange(String conversationId, String username) {
        try {
            jpaChatMemory.deleteLastMessages(conversationId, 2, username);
            log.debug("[EMPTY_RETRY] deleted empty exchange from memory");
        } catch (Exception e) {
            log.warn("[EMPTY_RETRY] failed to delete empty exchange: {}", e.getMessage());
        }
    }

    private void clearUserExecutionContext(String contextKey, String conversationId) {
        userContext.releaseToolSession(contextKey);
        if (conversationId != null && !conversationId.isBlank()) {
            userContext.releaseConversationSession(conversationId);
        }
    }

    private String buildDynamicSystemContext(String username, String conversationId, String toolContextKey) {
        if (username == null || username.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("未登入或 Session 已失效，請重新登入。");
        }
        String effectiveConversationId = conversationId == null ? "" : conversationId;
        String effectiveToolContextKey = toolContextKey == null ? "" : toolContextKey;
        return DYNAMIC_SYSTEM_CONTEXT_TEMPLATE.formatted(
                username,
                effectiveConversationId,
                effectiveToolContextKey);
    }

    private String registerUserExecutionContext(
            String toolContextKey, String conversationId, String username, String sessionId) {
        if (username == null || username.isBlank()) {
            return null;
        }
        userContext.registerToolSession(toolContextKey, username, sessionId);
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            userContext.registerConversationSession(conversationId, username, sessionId);
            return conversationId;
        } catch (IllegalStateException ex) {
            log.warn("Skip conversation session binding due to key collision. conversationId={}, username={}",
                    conversationId, username);
            return null;
        }
    }

}
