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
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Chat Controller
 *
 * HTTP layer for AI chat operations.
 * Delegates business logic to ChatService and ConversationService.
 *
 * Responsibilities:
 * - HTTP request/response handling
 * - Session management (user extraction)
 * - Parameter validation and defaults
 *
 * @author Claude Code - Phase 2 Refactoring
 * @version 2.0 (Refactored from 309 lines to ~100 lines)
 */
@RestController
@RequestMapping("/api/ai")
// CORS configuration centralized in SecurityConfig (whitelist mode)
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int OUTPUT_TOKEN_RESERVE_AI = 600;
    private static final int FIXED_INPUT_TOKEN_OVERHEAD_AI = 1_200;
    private static final long MAX_HISTORY_CHARS_FOR_ESTIMATE = 12_000L;
    private static final int HISTORY_ESTIMATE_PAGE_SIZE = 100;
    private static final int FALLBACK_MODEL_TPM = 8_000;
    private static final String FALLBACK_MODEL_BUCKET_KEY = "__default__";
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MIN_RETRY_AFTER_SECONDS = 1L;
    private static final long UNKNOWN_ACTUAL_TOKEN_USAGE = -1L;
    private record ActiveStreamSlot(String username, long startedAtMs) {}
    // ========== Dependencies ==========

    /**
     * In-flight deduplication set: prevents two concurrent HTTP requests for the
     * same (username, command) pair from both reaching the confirm/cancel logic.
     *
     * Keys are "username:command". The backend pending-confirmation consume is
     * already atomic, so this is defence-in-depth against double-click (two tabs,
     * rapid replay) rather than a primary security control.
     */
    private final Map<String, Long> inFlightConfirmKeys = new ConcurrentHashMap<>();
    private final Object inFlightConfirmKeysLock = new Object();
    private final Map<String, AtomicInteger> activeStreamsPerUser = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedDeque<Long>> activeStreamStartTimesPerUser = new ConcurrentHashMap<>();

    private final ChatService chatService;
    private final ConversationService conversationService;
    private final AiModelService aiModelService;
    private final AiModelProperties aiModelProperties;
    private final OffloadJobService offloadJobService;
    private final CommandJobService commandJobService;
    private final ChatRateLimiter chatRateLimiter;
    private final SlashCommandRateLimiter slashCommandRateLimiter;
    private final UserTpmLimiter userTpmLimiter;
    private final TpmBucket tpmBucket;
    private final Supplier<Long> currentTimeMsSupplier;
    private final long inFlightKeyTtlMs;
    private final long streamTimeoutMs;
    private final int maxConcurrentStreamsPerUser;

    // ========== Constructor ==========

    @Autowired
    public ChatController(
            ChatService chatService,
            ConversationService conversationService,
            AiModelService aiModelService,
            AiModelProperties aiModelProperties,
            OffloadJobService offloadJobService,
            CommandJobService commandJobService,
            ChatRateLimiter chatRateLimiter,
            SlashCommandRateLimiter slashCommandRateLimiter,
            UserTpmLimiter userTpmLimiter,
            TpmBucket tpmBucket,
            @Value("${app.chat.in-flight-confirm-ttl-ms:300000}") long inFlightKeyTtlMs,
            @Value("${app.security.chat.max-concurrent-streams-per-user:2}") int maxConcurrentStreamsPerUser) {
        this(
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
                System::currentTimeMillis,
                inFlightKeyTtlMs,
                maxConcurrentStreamsPerUser);
    }

    ChatController(
            ChatService chatService,
            ConversationService conversationService,
            AiModelService aiModelService,
            AiModelProperties aiModelProperties,
            OffloadJobService offloadJobService,
            CommandJobService commandJobService,
            ChatRateLimiter chatRateLimiter,
            SlashCommandRateLimiter slashCommandRateLimiter,
            UserTpmLimiter userTpmLimiter,
            TpmBucket tpmBucket,
            Supplier<Long> currentTimeMsSupplier,
            long inFlightKeyTtlMs,
            int maxConcurrentStreamsPerUser) {
        this.chatService = chatService;
        this.conversationService = conversationService;
        this.aiModelService = aiModelService;
        this.aiModelProperties = aiModelProperties;
        this.offloadJobService = offloadJobService;
        this.commandJobService = commandJobService;
        this.chatRateLimiter = chatRateLimiter;
        this.slashCommandRateLimiter = slashCommandRateLimiter;
        this.userTpmLimiter = userTpmLimiter;
        this.tpmBucket = tpmBucket;
        this.currentTimeMsSupplier = Objects.requireNonNullElse(currentTimeMsSupplier, System::currentTimeMillis);
        this.inFlightKeyTtlMs = Math.max(1_000L, inFlightKeyTtlMs);
        this.streamTimeoutMs = Math.max(1L, ChatService.STREAM_TIMEOUT_SECONDS) * MILLIS_PER_SECOND;
        this.maxConcurrentStreamsPerUser = Math.max(1, maxConcurrentStreamsPerUser);
    }

    // ========== Endpoints ==========

    /**
     * Gets available AI models (for frontend dynamic display).
     *
     * @return unified response with model configurations
     */
    @GetMapping("/models")
    public ApiResponse<Map<String, AiModelProperties.ModelConfig>> getModels() {
        return ApiResponse.success(aiModelService.getClientModelsAsMap());
    }

    /**
     * Streaming chat endpoint.
     *
     * @param message user's message
     * @param conversationId conversation ID (generated if null)
     * @param model model key (default: app.ai.default-model-key)
     * @param session HTTP session for user extraction
     * @return Flux stream of response chunks
     */
    @PostMapping(value = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamChat(
            @Valid @RequestBody ChatStreamRequest request,
            HttpSession session) {
        String message = request.getMessage().trim();
        String conversationId = (request.getConversationId() != null && !request.getConversationId().isBlank())
                ? request.getConversationId().trim()
                : null;
        String requestedModelKey = (request.getModel() != null && !request.getModel().isBlank())
                ? request.getModel().trim()
                : aiModelProperties.getDefaultModelKey();
        String username = requireAuthenticatedUser(session);
        ActiveStreamSlot activeStreamSlot = acquireActiveStreamSlot(username);
        boolean isDeterministicCommand = false;
        String effectiveModelKey = null;
        int effectiveTpm = 0;
        long estimatedTokens = 0L;
        UserTpmLimiter.ConsumeResult userTpmConsumeResult = null;
        TpmBucket.ConsumeResult globalTpmConsumeResult = null;
        AtomicLong actualTokenUsage = new AtomicLong(UNKNOWN_ACTUAL_TOKEN_USAGE);
        AtomicLong streamedCjkChars = new AtomicLong(0L);
        AtomicLong streamedOtherChars = new AtomicLong(0L);
        try {
            isDeterministicCommand = isDeterministicCommandMessage(message);
            if (isDeterministicCommand) {
                long retryAfter = slashCommandRateLimiter.tryConsume(username);
                if (retryAfter > 0) {
                    throw RateLimitExceededException.userRateLimit(retryAfter);
                }
            }
            String resolvedConversationId = conversationService.resolveConversationIdForStream(conversationId, username);
            Map<String, AiModelProperties.ModelConfig> models = aiModelService.getModelsAsMap();
            effectiveModelKey = resolveEffectiveModelKey(requestedModelKey, models);
            int modelTpm = resolveModelTpm(effectiveModelKey, models);
            int healthyKeyCount = aiModelService.countHealthyGroqApiKeys();
            if (!isDeterministicCommand && healthyKeyCount <= 0) {
                long retryAfterSeconds = resolveNoHealthyKeyRetryAfterSeconds();
                log.warn(
                        "AI stream rejected before TPM consume because all keys are cooling down: user='{}', model='{}', retryAfter={}s",
                        username,
                        effectiveModelKey,
                        retryAfterSeconds);
                throw RateLimitExceededException.globalTpmLimit(retryAfterSeconds);
            }
            effectiveTpm = scaleModelTpmByKeyCount(modelTpm, Math.max(1, healthyKeyCount));
            if (!isDeterministicCommand) {
                estimatedTokens = estimateRequestedTokens(message, resolvedConversationId, username);
                long tpmRetryAfter = tpmBucket.peek(effectiveModelKey, effectiveTpm, estimatedTokens);
                if (tpmRetryAfter > 0) {
                    log.warn(
                            "Global TPM bucket limit exceeded before user consume: user='{}', model='{}', estimatedTokens={}, modelTpm={}, healthyKeyCount={}, effectiveTpm={}, retryAfter={}s",
                            username,
                            effectiveModelKey,
                            estimatedTokens,
                            modelTpm,
                            healthyKeyCount,
                            effectiveTpm,
                            tpmRetryAfter);
                    throw RateLimitExceededException.globalTpmLimit(tpmRetryAfter);
                }
                userTpmConsumeResult =
                        userTpmLimiter.tryConsumeWithHandle(username, estimatedTokens, effectiveTpm);
                long userTpmRetryAfter = userTpmConsumeResult.retryAfterSeconds();
                if (userTpmRetryAfter > 0) {
                    log.warn(
                            "Per-user TPM limit exceeded: user='{}', model='{}', estimatedTokens={}, effectiveTpm={}, retryAfter={}s",
                            username,
                            effectiveModelKey,
                            estimatedTokens,
                            effectiveTpm,
                            userTpmRetryAfter);
                    throw RateLimitExceededException.userTpmLimit(userTpmRetryAfter);
                }
                long userRateRetryAfter = chatRateLimiter.tryConsume(username);
                if (userRateRetryAfter > 0) {
                    boolean rolledBack = userTpmLimiter.rollback(username, userTpmConsumeResult.eventId());
                    if (rolledBack) {
                        userTpmConsumeResult = null;
                    }
                    log.warn(
                            "Per-user fallback request-rate limit exceeded after user TPM consume: user='{}', model='{}', estimatedTokens={}, retryAfter={}s, userRollback={}",
                            username,
                            effectiveModelKey,
                            estimatedTokens,
                            userRateRetryAfter,
                            rolledBack);
                    throw RateLimitExceededException.userRateLimit(userRateRetryAfter);
                }
                globalTpmConsumeResult = tpmBucket.consumeWithResult(effectiveModelKey, effectiveTpm, estimatedTokens);
                long consumeRetryAfter = globalTpmConsumeResult.retryAfterSeconds();
                if (consumeRetryAfter > 0) {
                    boolean rolledBack = userTpmLimiter.rollback(username, userTpmConsumeResult.eventId());
                    if (rolledBack) {
                        userTpmConsumeResult = null;
                    }
                    globalTpmConsumeResult = null;
                    log.warn(
                            "Global TPM bucket limit exceeded during final consume: user='{}', model='{}', estimatedTokens={}, modelTpm={}, healthyKeyCount={}, effectiveTpm={}, retryAfter={}s, userRollback={}",
                            username,
                            effectiveModelKey,
                            estimatedTokens,
                            modelTpm,
                            healthyKeyCount,
                            effectiveTpm,
                            consumeRetryAfter,
                            rolledBack);
                    throw RateLimitExceededException.globalTpmLimit(consumeRetryAfter);
                }
            }
            Flux<String> stream = isDeterministicCommand
                    ? chatService.streamChat(
                            message,
                            resolvedConversationId,
                            effectiveModelKey,
                            username,
                            session.getId())
                    : chatService.streamChat(
                            message,
                            resolvedConversationId,
                            effectiveModelKey,
                            username,
                            session.getId(),
                            actualTokenUsage::set);
            if (isDeterministicCommand) {
                return stream.doFinally(signal -> releaseActiveStreamSlot(activeStreamSlot));
            }
            Flux<String> trackedStream = stream.doOnNext(chunk -> {
                if (chunk != null && !chunk.isEmpty()) {
                    long cjkChars = chunk.codePoints().filter(this::isCjkCodePoint).count();
                    long otherChars = Math.max(0L, chunk.length() - cjkChars);
                    streamedCjkChars.addAndGet(cjkChars);
                    streamedOtherChars.addAndGet(otherChars);
                }
            });
            String finalEffectiveModelKey = effectiveModelKey;
            int finalEffectiveTpm = effectiveTpm;
            long finalEstimatedTokens = estimatedTokens;
            UserTpmLimiter.ConsumeResult finalUserTpmConsumeResult = userTpmConsumeResult;
            TpmBucket.ConsumeResult finalGlobalTpmConsumeResult = globalTpmConsumeResult;
            return trackedStream.doFinally(signal -> {
                reconcileTokenUsageAfterStream(
                        username,
                        finalEffectiveModelKey,
                        finalEffectiveTpm,
                        finalEstimatedTokens,
                        finalUserTpmConsumeResult,
                        finalGlobalTpmConsumeResult,
                        actualTokenUsage.get(),
                        streamedCjkChars.get() + streamedOtherChars.get(),
                        estimateTokensFromCharBreakdown(streamedCjkChars.get(), streamedOtherChars.get()),
                        signal);
                releaseActiveStreamSlot(activeStreamSlot);
            });
        } catch (RuntimeException ex) {
            reconcileTokenUsageAfterStream(
                    username,
                    effectiveModelKey,
                    effectiveTpm,
                    estimatedTokens,
                    userTpmConsumeResult,
                    globalTpmConsumeResult,
                    actualTokenUsage.get(),
                    streamedCjkChars.get() + streamedOtherChars.get(),
                    estimateTokensFromCharBreakdown(streamedCjkChars.get(), streamedOtherChars.get()),
                    SignalType.ON_ERROR);
            releaseActiveStreamSlot(activeStreamSlot);
            throw ex;
        }
    }

    /**
     * Clears conversation memory.
     *
     * @param conversationId the conversation ID to clear
     * @return unified response with success message
     */
    @DeleteMapping("/history")
    public ApiResponse<Void> clearHistory(@RequestParam String conversationId, HttpSession session) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String username = requireConversationOwnership(session, normalizedConversationId, "clear history");
        conversationService.clearHistory(normalizedConversationId, username);
        return ApiResponse.success("記憶已清除");
    }

    /**
     * Deletes the last N messages from a conversation.
     * Used by the frontend regenerate flow to remove stale user+AI exchange before resending.
     *
     * @param conversationId the conversation ID
     * @param count number of messages to remove from the end (default 2)
     * @param session HTTP session for user extraction
     * @return unified response
     */
    @DeleteMapping("/history/last-messages")
    public ApiResponse<Void> deleteLastMessages(
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "2") int count,
            HttpSession session) {
        String normalizedConversationId = normalizeConversationId(conversationId);
        String username = requireConversationOwnership(session, normalizedConversationId, "delete messages");
        if (count < 1 || count > 20) {
            throw new IllegalArgumentException("count 必須介於 1 到 20 之間");
        }
        conversationService.deleteLastMessages(normalizedConversationId, count, username);
        return ApiResponse.success("訊息已刪除");
    }

    /**
     * Gets a page of conversation history (for frontend initialization and "load more").
     *
     * @param conversationId the conversation ID
     * @param limit          max messages per page (1–100, default 50)
     * @param offset         how many most-recent messages to skip (default 0 = latest page)
     * @param beforeCreatedAt cursor timestamp (ISO-8601 local datetime), optional
     * @param beforeId        cursor ID, must be paired with beforeCreatedAt
     * @return unified response with messages list + total count
     */
    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> getHistory(
            @RequestParam String conversationId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(required = false) String beforeCreatedAt,
            @RequestParam(required = false) Long beforeId,
            HttpSession session) {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit 必須介於 1 到 100 之間");
        if (offset < 0) throw new IllegalArgumentException("offset 不得為負數");

        String trimmedBeforeCreatedAt = beforeCreatedAt == null ? null : beforeCreatedAt.trim();
        boolean hasCursorCreatedAt = trimmedBeforeCreatedAt != null && !trimmedBeforeCreatedAt.isBlank();
        boolean hasCursorId = beforeId != null;
        if (hasCursorCreatedAt != hasCursorId) {
            throw new IllegalArgumentException("beforeCreatedAt 與 beforeId 必須同時提供");
        }
        if (hasCursorCreatedAt && offset != 0) {
            throw new IllegalArgumentException("cursor 分頁模式不支援 offset");
        }

        String normalizedConversationId = normalizeConversationId(conversationId);
        String username = requireConversationOwnership(session, normalizedConversationId, "read history");
        if (hasCursorCreatedAt) {
            LocalDateTime parsedBeforeCreatedAt;
            try {
                parsedBeforeCreatedAt = LocalDateTime.parse(trimmedBeforeCreatedAt);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException("beforeCreatedAt 格式錯誤，需為 ISO-8601 LocalDateTime", ex);
            }
            return ApiResponse.success(conversationService.getRecentHistoryByCursor(
                    normalizedConversationId,
                    username,
                    limit,
                    parsedBeforeCreatedAt,
                    beforeId
            ));
        }
        return ApiResponse.success(conversationService.getRecentHistory(normalizedConversationId, username, limit, offset));
    }

    public ApiResponse<Map<String, Object>> getHistory(
            String conversationId,
            int limit,
            int offset,
            HttpSession session) {
        return getHistory(conversationId, limit, offset, null, null, session);
    }

    /**
     * Directly executes a confirmed command, bypassing the AI model.
     *
     * Called when the user clicks the confirmation button in the frontend.
     * This avoids relying on the AI to re-invoke the correct tool after confirmation,
     * which is unreliable with smaller models.
     *
     * @param command the confirmed command (e.g. "rm -rf /some/path")
     * @param session HTTP session for user extraction
     * @return unified response with execution result
     */
    @PostMapping("/confirm-command")
    public ApiResponse<String> confirmCommand(
            @Valid @RequestBody ConfirmCommandRequest request,
            HttpSession session) {
        String username = requireAuthenticatedUser(session);
        String command = request.getCommand().trim();
        String conversationId = normalizeConversationId(request.getConversationId());
        requireConversationOwnershipIfPresent(username, conversationId, "confirm command");
        String inFlightKey = "confirm:" + username + ":" + command;
        if (!tryAcquireInFlightKey(inFlightKey)) {
            log.warn("DUPLICATE: user '{}' sent concurrent confirm for cmd='{}'", username, command);
            throw new IllegalArgumentException("此指令正在處理中，請勿重複送出。");
        }
        try {
            String result = chatService.executeConfirmedCommand(command, username, conversationId, session.getId());
            return ApiResponse.success("執行成功", result);
        } finally {
            releaseInFlightKey(inFlightKey);
        }
    }

    @GetMapping("/offload-progress/{jobId}")
    public ApiResponse<OffloadJobService.OffloadProgressSnapshot> getOffloadProgress(
            @PathVariable String jobId,
            HttpSession session) {
        String username = requireAuthenticatedUser(session);
        return offloadJobService.getJobProgress(username, jobId)
                .map(ApiResponse::success)
                .orElseThrow(() -> new IllegalArgumentException("找不到 offload 任務或無權限存取。"));
    }

    @GetMapping("/command-job-progress/{jobId}")
    public ApiResponse<CommandJobService.CommandJobProgressSnapshot> getCommandJobProgress(
            @PathVariable String jobId,
            HttpSession session) {
        String username = requireAuthenticatedUser(session);
        return commandJobService.getJobProgress(username, jobId)
                .map(ApiResponse::success)
                .orElseThrow(() -> new IllegalArgumentException("找不到背景命令任務或無權限存取。"));
    }

    /**
     * Cancels a pending command directly, bypassing the AI model.
     *
     * Called when the user clicks the cancel button in the frontend.
     * Clears pending state and adds an AssistantMessage to memory
     * so the next AI call has clean, coherent history.
     *
     * @param conversationId conversation ID for memory update
     * @param session HTTP session for user extraction
     * @return unified response
     */
    @PostMapping("/cancel-command")
    public ApiResponse<Void> cancelCommand(
            @RequestBody(required = false) @Valid CancelCommandRequest request,
            HttpSession session) {
        String username = requireAuthenticatedUser(session);
        String conversationId = normalizeConversationId(request != null ? request.getConversationId() : null);
        String command = (request != null && request.getCommand() != null && !request.getCommand().isBlank())
                ? request.getCommand().trim()
                : null;
        requireConversationOwnershipIfPresent(username, conversationId, "cancel command");
        String inFlightKey = "cancel:" + username + ":" + (command != null ? command : "");
        if (!tryAcquireInFlightKey(inFlightKey)) {
            log.debug("DUPLICATE: user '{}' sent concurrent cancel for cmd='{}'", username, command);
            return ApiResponse.success("已取消操作");
        }
        try {
            chatService.cancelPendingCommand(username, conversationId, command);
            return ApiResponse.success("已取消操作");
        } finally {
            releaseInFlightKey(inFlightKey);
        }
    }

    /**
     * Creates a new conversation and returns a server-generated conversation ID.
     * The client must call this endpoint to obtain a conversation ID before starting a new chat,
     * ensuring all conversation IDs are generated server-side.
     *
     * @param session HTTP session for user extraction
     * @return unified response with the new conversation ID
     */
    @PostMapping("/conversations/new")
    public ApiResponse<String> createConversation(HttpSession session) {
        requireAuthenticatedUser(session);
        String newId = UUID.randomUUID().toString();
        return ApiResponse.success("新對話已建立", newId);
    }

    /**
     * Gets all conversations for the current user.
     *
     * @param session HTTP session for user extraction
     * @return unified response with list of conversations
     */
    @GetMapping("/conversations")
    public ApiResponse<List<Map<String, Object>>> getConversations(HttpSession session) {
        String username = requireAuthenticatedUser(session);
        return ApiResponse.success(conversationService.getConversationsForUser(username));
    }

    private String requireAuthenticatedUser(HttpSession session) {
        String username = (String) session.getAttribute("user");
        if (username == null || username.isBlank()) {
            throw new AuthenticationCredentialsNotFoundException("未登入或 Session 已失效，請重新登入。");
        }
        return username;
    }

    private String requireConversationOwnership(HttpSession session, String conversationId, String action) {
        String username = requireAuthenticatedUser(session);
        if (conversationId == null) {
            log.warn("SECURITY: user '{}' attempted to {} with empty conversation id", username, action);
            throw new AccessDeniedException("無權存取該對話");
        }
        requireConversationOwnershipIfPresent(username, conversationId, action);
        return username;
    }

    private void requireConversationOwnershipIfPresent(String username, String conversationId, String action) {
        if (conversationId == null) {
            return;
        }
        if (!conversationService.isConversationOwnedByUser(conversationId, username)) {
            log.warn(
                    "SECURITY: user '{}' attempted to {} for conversation '{}' without ownership",
                    username,
                    action,
                    conversationId);
            throw new AccessDeniedException("無權存取該對話");
        }
    }

    private String normalizeConversationId(String conversationId) {
        if (conversationId == null) {
            return null;
        }
        String trimmedConversationId = conversationId.trim();
        return trimmedConversationId.isEmpty() ? null : trimmedConversationId;
    }

    private boolean tryAcquireInFlightKey(String key) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        AtomicLong recoveredAgeMs = new AtomicLong(-1L);
        synchronized (inFlightConfirmKeysLock) {
            long now = currentTimeMsSupplier.get();
            inFlightConfirmKeys.compute(key, (ignored, existingSince) -> {
                if (existingSince == null) {
                    acquired.set(true);
                    return now;
                }
                long ageMs = now - existingSince;
                if (ageMs >= inFlightKeyTtlMs) {
                    acquired.set(true);
                    recoveredAgeMs.set(ageMs);
                    return now;
                }
                return existingSince;
            });
        }
        long staleAgeMs = recoveredAgeMs.get();
        if (staleAgeMs >= 0L) {
            log.warn("Recovered stale in-flight key '{}' (ageMs={})", key, staleAgeMs);
        }
        return acquired.get();
    }

    private void releaseInFlightKey(String key) {
        synchronized (inFlightConfirmKeysLock) {
            inFlightConfirmKeys.remove(key);
        }
    }

    private ActiveStreamSlot acquireActiveStreamSlot(String username) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        AtomicLong activeBeforeReject = new AtomicLong(0L);
        long nowMs = currentTimeMsSupplier.get();
        ConcurrentLinkedDeque<Long> startedAtMsQueue =
                activeStreamStartTimesPerUser.computeIfAbsent(username, ignored -> new ConcurrentLinkedDeque<>());
        activeStreamsPerUser.compute(username, (ignored, existingCounter) -> {
            AtomicInteger streamCounter = existingCounter != null ? existingCounter : new AtomicInteger(0);
            int activeStreams = streamCounter.incrementAndGet();
            if (activeStreams > maxConcurrentStreamsPerUser) {
                streamCounter.decrementAndGet();
                activeBeforeReject.set(activeStreams - 1L);
                return streamCounter.get() == 0 ? null : streamCounter;
            }
            acquired.set(true);
            startedAtMsQueue.addLast(nowMs);
            return streamCounter;
        });
        if (!acquired.get()) {
            if (startedAtMsQueue.isEmpty()) {
                activeStreamStartTimesPerUser.remove(username, startedAtMsQueue);
            }
            long retryAfterSeconds = calculateConcurrentStreamRetryAfterSeconds(startedAtMsQueue, nowMs);
            log.warn(
                    "Concurrent stream limit exceeded for user '{}': active={}, max={}, retryAfter={}s",
                    username,
                    activeBeforeReject.get(),
                    maxConcurrentStreamsPerUser,
                    retryAfterSeconds);
            throw new ConcurrentStreamLimitExceededException(maxConcurrentStreamsPerUser, retryAfterSeconds);
        }
        return new ActiveStreamSlot(username, nowMs);
    }

    private long calculateConcurrentStreamRetryAfterSeconds(ConcurrentLinkedDeque<Long> startedAtMsQueue, long nowMs) {
        Long oldestStartedAtMs = startedAtMsQueue.peekFirst();
        if (oldestStartedAtMs == null) {
            return MIN_RETRY_AFTER_SECONDS;
        }
        long elapsedMs = Math.max(0L, nowMs - oldestStartedAtMs);
        long remainingMs = streamTimeoutMs - elapsedMs;
        if (remainingMs <= 0L) {
            return MIN_RETRY_AFTER_SECONDS;
        }
        return Math.max(MIN_RETRY_AFTER_SECONDS, (remainingMs + (MILLIS_PER_SECOND - 1L)) / MILLIS_PER_SECOND);
    }

    private long resolveNoHealthyKeyRetryAfterSeconds() {
        long cooldownMs = Math.max(0L, aiModelService.getKeyCooldownMs());
        return Math.max(MIN_RETRY_AFTER_SECONDS, (cooldownMs + (MILLIS_PER_SECOND - 1L)) / MILLIS_PER_SECOND);
    }

    private void releaseActiveStreamSlot(ActiveStreamSlot activeStreamSlot) {
        String username = activeStreamSlot.username();
        activeStreamsPerUser.computeIfPresent(username, (ignored, existingCounter) -> {
            int remaining = existingCounter.decrementAndGet();
            if (remaining <= 0) {
                existingCounter.set(0);
                return null;
            }
            return existingCounter;
        });
        activeStreamStartTimesPerUser.computeIfPresent(username, (ignored, startedAtMsQueue) -> {
            startedAtMsQueue.removeFirstOccurrence(activeStreamSlot.startedAtMs());
            return startedAtMsQueue.isEmpty() ? null : startedAtMsQueue;
        });
    }

    private String resolveEffectiveModelKey(
            String requestedModelKey,
            Map<String, AiModelProperties.ModelConfig> models) {
        if (requestedModelKey != null
                && !requestedModelKey.isBlank()
                && models != null
                && models.containsKey(requestedModelKey)) {
            return requestedModelKey;
        }
        String defaultModelKey = aiModelProperties.getDefaultModelKey();
        if (defaultModelKey != null
                && !defaultModelKey.isBlank()
                && models != null
                && models.containsKey(defaultModelKey)) {
            return defaultModelKey;
        }
        if (defaultModelKey != null && !defaultModelKey.isBlank()) {
            return defaultModelKey;
        }
        if (models != null && !models.isEmpty()) {
            return models.keySet().iterator().next();
        }
        return FALLBACK_MODEL_BUCKET_KEY;
    }

    private int resolveModelTpm(
            String effectiveModelKey,
            Map<String, AiModelProperties.ModelConfig> models) {
        if (models != null) {
            AiModelProperties.ModelConfig config = models.get(effectiveModelKey);
            if (config != null && config.getTpm() > 0) {
                return config.getTpm();
            }

            String defaultModelKey = aiModelProperties.getDefaultModelKey();
            AiModelProperties.ModelConfig defaultConfig = defaultModelKey == null ? null : models.get(defaultModelKey);
            if (defaultConfig != null && defaultConfig.getTpm() > 0) {
                return defaultConfig.getTpm();
            }
        }
        return FALLBACK_MODEL_TPM;
    }

    private int scaleModelTpmByKeyCount(int modelTpm, int keyCount) {
        long safeModelTpm = Math.max(1L, modelTpm);
        long safeKeyCount = Math.max(1L, keyCount);
        long scaledTpm = safeModelTpm * safeKeyCount;
        return (int) Math.min(Integer.MAX_VALUE, scaledTpm);
    }

    private boolean isDeterministicCommandMessage(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String trimmed = message.trim();
        return trimmed.startsWith("/") || trimmed.startsWith("!");
    }

    private long estimateRequestedTokens(String message, String conversationId, String username) {
        long historyTokens = estimateHistoryTokens(conversationId, username);
        long messageTokens = estimateTokens(message);
        // This estimator is called only for non-deterministic AI requests.
        long estimatedInputTokens = historyTokens + messageTokens + FIXED_INPUT_TOKEN_OVERHEAD_AI;
        int reserve = OUTPUT_TOKEN_RESERVE_AI;
        return Math.max(1L, estimatedInputTokens + reserve);
    }

    private long estimateHistoryTokens(String conversationId, String username) {
        if (conversationId == null || conversationId.isBlank() || username == null || username.isBlank()) {
            return 0L;
        }
        long remainingChars = MAX_HISTORY_CHARS_FOR_ESTIMATE;
        long totalTokens = 0L;
        LocalDateTime beforeCreatedAt = null;
        Long beforeId = null;

        while (remainingChars > 0L) {
            Map<String, Object> historyPage = conversationService.getRecentHistoryByCursor(
                    conversationId,
                    username,
                    HISTORY_ESTIMATE_PAGE_SIZE,
                    beforeCreatedAt,
                    beforeId);
            if (historyPage == null || historyPage.isEmpty()) {
                break;
            }

            Object messagesObject = historyPage.get("messages");
            if (!(messagesObject instanceof List<?> messages) || messages.isEmpty()) {
                break;
            }

            for (int i = messages.size() - 1; i >= 0 && remainingChars > 0L; i--) {
                Object rawMessage = messages.get(i);
                if (!(rawMessage instanceof Map<?, ?> messageMap)) {
                    continue;
                }
                String content = Objects.toString(messageMap.get("content"), "");
                if (content.isEmpty()) {
                    continue;
                }
                if (content.length() <= remainingChars) {
                    totalTokens += estimateTokens(content);
                    remainingChars -= content.length();
                    continue;
                }
                int sampledLength = (int) Math.min((long) content.length(), remainingChars);
                totalTokens += estimateTokens(content.substring(content.length() - sampledLength));
                remainingChars = 0L;
            }

            if (remainingChars <= 0L) {
                break;
            }

            Object nextCursorCreatedAtObject = historyPage.get("nextCursorCreatedAt");
            Object nextCursorIdObject = historyPage.get("nextCursorId");
            if (!(nextCursorCreatedAtObject instanceof String nextCursorCreatedAt)
                    || nextCursorCreatedAt.isBlank()
                    || !(nextCursorIdObject instanceof Number nextCursorIdNumber)) {
                break;
            }
            try {
                beforeCreatedAt = LocalDateTime.parse(nextCursorCreatedAt);
            } catch (DateTimeParseException ex) {
                log.debug("Skip malformed nextCursorCreatedAt while estimating history tokens: {}", nextCursorCreatedAt, ex);
                break;
            }
            beforeId = nextCursorIdNumber.longValue();
        }
        return totalTokens;
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        long cjkChars = text.codePoints().filter(this::isCjkCodePoint).count();
        long otherChars = text.length() - cjkChars;
        return estimateTokensFromCharBreakdown(cjkChars, otherChars);
    }

    private boolean isCjkCodePoint(int codePoint) {
        return codePoint >= 0x4E00 && codePoint <= 0x9FFF;
    }

    private long estimateTokensFromCharBreakdown(long cjkChars, long otherChars) {
        if (cjkChars <= 0L && otherChars <= 0L) {
            return 0L;
        }
        return cjkChars * 2L + otherChars / 3L + 1L;
    }

    private void reconcileTokenUsageAfterStream(
            String username,
            String modelKey,
            int effectiveTpm,
            long estimatedTokens,
            UserTpmLimiter.ConsumeResult userConsumeResult,
            TpmBucket.ConsumeResult globalConsumeResult,
            long actualTokenUsage,
            long streamedResponseChars,
            long estimatedOutputTokens,
            SignalType signalType) {
        long userEventId = userConsumeResult == null ? 0L : userConsumeResult.eventId();
        long reservedUserTokens = resolveReservedTokens(
                userEventId > 0L,
                userConsumeResult == null ? 0L : userConsumeResult.consumedTokens(),
                estimatedTokens);
        long reservedGlobalTokens = resolveReservedTokens(
                globalConsumeResult != null && globalConsumeResult.consumedTokens() > 0L,
                globalConsumeResult == null ? 0L : globalConsumeResult.consumedTokens(),
                estimatedTokens);

        if (reservedUserTokens <= 0L && reservedGlobalTokens <= 0L) {
            return;
        }

        if (actualTokenUsage < 0L) {
            long estimatedInputTokens = Math.max(0L, estimatedTokens - OUTPUT_TOKEN_RESERVE_AI);
            long estimatedOutputActual = Math.max(0L, estimatedOutputTokens);
            long estimatedActual = Math.max(0L, estimatedInputTokens + estimatedOutputActual);
            long boundedUserActual = reservedUserTokens > 0L
                    ? Math.min(estimatedActual, reservedUserTokens)
                    : 0L;
            long boundedGlobalActual = reservedGlobalTokens > 0L
                    ? Math.min(estimatedActual, reservedGlobalTokens)
                    : 0L;
            long userDelta = boundedUserActual - reservedUserTokens;
            long globalDelta = boundedGlobalActual - reservedGlobalTokens;
            long userAppliedDelta = 0L;
            if (userEventId > 0L && userDelta != 0L) {
                userAppliedDelta = userTpmLimiter.adjustEventTokens(username, userEventId, userDelta);
            }
            long globalAppliedDelta = globalDelta == 0L
                    ? 0L
                    : tpmBucket.adjustConsumption(modelKey, effectiveTpm, globalDelta);
            log.info(
                    "TPM reconcile fallback without usage metadata: user='{}', model='{}', signal={}, estimatedTokens={}, streamedResponseChars={}, estimatedInputTokens={}, estimatedOutputTokens={}, estimatedActualTokens={}, reservedUserTokens={}, reservedGlobalTokens={}, boundedUserActual={}, boundedGlobalActual={}, userDelta={}, userAppliedDelta={}, globalDelta={}, globalAppliedDelta={}",
                    username,
                    modelKey,
                    signalType,
                    estimatedTokens,
                    streamedResponseChars,
                    estimatedInputTokens,
                    estimatedOutputActual,
                    estimatedActual,
                    reservedUserTokens,
                    reservedGlobalTokens,
                    boundedUserActual,
                    boundedGlobalActual,
                    userDelta,
                    userAppliedDelta,
                    globalDelta,
                    globalAppliedDelta);
            return;
        }

        long userDelta = actualTokenUsage - reservedUserTokens;
        long globalDelta = actualTokenUsage - reservedGlobalTokens;
        long appliedUserDelta = 0L;
        if (userEventId > 0L && userDelta != 0L) {
            appliedUserDelta = userTpmLimiter.adjustEventTokens(username, userEventId, userDelta);
        }
        long appliedGlobalDelta = globalDelta == 0L
                ? 0L
                : tpmBucket.adjustConsumption(modelKey, effectiveTpm, globalDelta);
        if (appliedUserDelta != 0L || appliedGlobalDelta != 0L) {
            log.info(
                    "TPM reconciled from actual usage: user='{}', model='{}', signal={}, estimatedTokens={}, actualTokens={}, userDelta={}, userAppliedDelta={}, globalDelta={}, globalAppliedDelta={}",
                    username,
                    modelKey,
                    signalType,
                    estimatedTokens,
                    actualTokenUsage,
                    userDelta,
                    appliedUserDelta,
                    globalDelta,
                    appliedGlobalDelta);
        }
    }

    private long resolveReservedTokens(boolean reservationExists, long consumedTokens, long estimatedTokens) {
        if (!reservationExists) {
            return 0L;
        }
        if (consumedTokens > 0L) {
            return consumedTokens;
        }
        return Math.max(0L, estimatedTokens);
    }
}
