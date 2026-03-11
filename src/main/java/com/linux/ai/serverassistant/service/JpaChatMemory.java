package com.linux.ai.serverassistant.service;

import com.linux.ai.serverassistant.entity.ChatMessage;
import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Transactional
public class JpaChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(JpaChatMemory.class);
    private static final String ASSISTANT_TYPE = "ASSISTANT";
    private static final int DEFAULT_GET_MAX_MESSAGES = 200;
    private static final int DEFAULT_MAX_MESSAGES_PER_CONVERSATION = 1000;
    private static final int TRIM_CHECK_INTERVAL = 20;
    private static final int TRIM_COUNTER_CLEANUP_INTERVAL = 256;
    private static final long TRIM_COUNTER_TTL_MILLIS = 30L * 60L * 1000L;
    private static final Map<String, MessageType> MESSAGE_TYPE_MAP = Arrays.stream(MessageType.values())
            .flatMap(t -> Stream.of(Map.entry(t.name().toLowerCase(), t), Map.entry(t.getValue().toLowerCase(), t)))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a));

    private final ChatMessageRepository repository;
    private final UserContext userContext;
    private final TransactionTemplate requiresNewTx;

    @Value("${app.chat.memory.max-messages-per-conversation:" + DEFAULT_MAX_MESSAGES_PER_CONVERSATION + "}")
    private int maxMessagesPerConversation = DEFAULT_MAX_MESSAGES_PER_CONVERSATION;
    private final AtomicBoolean trimDisabledWarningLogged = new AtomicBoolean(false);
    private final ConcurrentMap<String, TrimCheckState> trimCheckCounters = new ConcurrentHashMap<>();
    private final AtomicInteger trimCounterCleanupTicker = new AtomicInteger(0);

    private static final class TrimCheckState {
        private final ReentrantLock lock = new ReentrantLock();
        private int pendingMessages;
        private volatile long lastTouchedAtMillis;

        private TrimCheckState(long nowMillis) {
            this.lastTouchedAtMillis = nowMillis;
        }
    }

    public record CursorPage(List<Message> messages, LocalDateTime nextCursorCreatedAt, Long nextCursorId) {}

    @Autowired
    public JpaChatMemory(ChatMessageRepository repository, UserContext userContext,
                         PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.userContext = userContext;
        this.requiresNewTx = new TransactionTemplate(transactionManager);
        this.requiresNewTx.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        add(conversationId, messages, null);
    }

    public void add(String conversationId, List<Message> messages, String usernameOverride) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String username = resolveCurrentUsername(conversationId, usernameOverride);
        List<ChatMessage> entities = new ArrayList<>(messages.size());

        for (Message message : messages) {
            if (message instanceof AssistantMessage && message.getText().isBlank()) {
                log.debug("add: skipping blank AssistantMessage for conversationId='{}'", conversationId);
                continue;
            }
            ChatMessage entity = new ChatMessage();
            entity.setConversationId(conversationId);
            entity.setUsername(username);
            entity.setContent(message.getText());
            entity.setMessageType(message.getMessageType().getValue());
            entities.add(entity);
        }

        if (entities.isEmpty()) {
            return;
        }
        repository.saveAll(entities);
        trimConversationIfNeeded(conversationId, entities.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Message> get(String conversationId) {
        return get(conversationId, DEFAULT_GET_MAX_MESSAGES);
    }

    @Transactional(readOnly = true)
    public List<Message> get(String conversationId, int lastN) {
        return get(conversationId, lastN, 0);
    }

    /**
     * Returns up to {@code limit} messages for the conversation, skipping the {@code offset}
     * most-recent messages. {@code offset} must be a non-negative multiple of {@code limit}
     * for correct page alignment (offset is integer-divided by limit internally).
     *
     * <ul>
     *   <li>offset=0, limit=50 → the 50 most-recent messages in chronological order</li>
     *   <li>offset=50, limit=50 → messages 51–100 from the end (older batch)</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<Message> get(String conversationId, int limit, int offset) {
        if (conversationId == null || conversationId.isBlank() || limit <= 0 || offset < 0) {
            return List.of();
        }
        if (offset % limit != 0) {
            throw new IllegalArgumentException("offset must be a multiple of limit");
        }
        int page = offset / limit;
        Pageable pageable = PageRequest.of(page, limit);
        List<ChatMessage> latestDesc = new ArrayList<>(
                repository.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId, pageable)
        );
        Collections.reverse(latestDesc);
        return latestDesc.stream().map(this::mapToMessage).toList();
    }

    /**
     * Keyset pagination for history queries. Returns the next cursor as the oldest
     * row in the returned page so callers can continue fetching older messages.
     */
    @Transactional(readOnly = true)
    public CursorPage getByCursor(String conversationId, int limit, LocalDateTime beforeCreatedAt, Long beforeId) {
        if (conversationId == null || conversationId.isBlank() || limit <= 0) {
            return new CursorPage(List.of(), null, null);
        }
        if ((beforeCreatedAt == null) != (beforeId == null)) {
            throw new IllegalArgumentException("beforeCreatedAt and beforeId must be provided together");
        }

        Pageable pageable = PageRequest.of(0, limit);
        List<ChatMessage> latestDesc = new ArrayList<>(
                beforeCreatedAt == null
                        ? repository.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId, pageable)
                        : repository.findHistoryPageByCursor(conversationId, beforeCreatedAt, beforeId, pageable)
        );
        if (latestDesc.isEmpty()) {
            return new CursorPage(List.of(), null, null);
        }

        ChatMessage oldestInPage = latestDesc.get(latestDesc.size() - 1);
        Collections.reverse(latestDesc);
        List<Message> messages = latestDesc.stream().map(this::mapToMessage).toList();
        return new CursorPage(messages, oldestInPage.getCreatedAt(), oldestInPage.getId());
    }

    @Override
    public void clear(String conversationId) {
        repository.deleteByConversationId(conversationId);
        if (conversationId != null && !conversationId.isBlank()) {
            trimCheckCounters.remove(conversationId);
        }
    }

    /**
     * Deletes the last {@code count} messages for the given conversation.
     * The operation is allowed only when the conversation belongs to {@code username}.
     *
     * Used by the cancel flow to remove the orphaned exchange left by a failed
     * deletePath tool call (UserMsg + empty AsstMsg) so the next delete request
     * starts from a clean history with no confusing empty assistant messages.
     */
    public void deleteLastMessages(String conversationId, int count, String username) {
        if (conversationId == null || conversationId.isBlank() || count <= 0) return;
        if (username == null || username.isBlank()) return;
        String normalizedUsername = username.trim();
        repository.deleteLastByConversationIdAndUsername(conversationId, normalizedUsername, count);
    }

    /**
     * Replaces the content of the most recent ASSISTANT message in the conversation.
     * If the last message is not an ASSISTANT message, a new AssistantMessage is appended instead.
     */
    public void replaceLastAssistantMessage(String conversationId, String newContent) {
        replaceLastAssistantMessage(conversationId, newContent, null);
    }

    public void replaceLastAssistantMessage(String conversationId, String newContent, String usernameOverride) {
        if (conversationId == null || conversationId.isBlank()) return;
        Optional<ChatMessage> latest = repository.findTop1ByConversationIdOrderByCreatedAtDescIdDesc(conversationId);
        if (latest.isPresent() && ASSISTANT_TYPE.equalsIgnoreCase(latest.get().getMessageType())) {
            ChatMessage last = latest.get();
            last.setContent(newContent);
            repository.save(last);
            return;
        }
        add(conversationId, List.of(new AssistantMessage(newContent)), usernameOverride);
    }

    /**
     * Replaces the latest assistant confirmation prompt that matches the given command.
     *
     * @return true when a matching assistant message is replaced, false otherwise.
     */
    public boolean replaceLatestAssistantCommandPrompt(String conversationId, String command, String newContent) {
        if (conversationId == null || conversationId.isBlank()) return false;
        if (command == null || command.isBlank()) return false;

        String trimmedCommand = command.trim();
        String marker = CommandMarkers.cmdMarker(trimmedCommand);
        String confirmMarker = CommandMarkers.confirmCmdMarker(trimmedCommand);
        List<ChatMessage> candidates = repository.findLatestByConversationIdAndMessageTypeAndContentMarkers(
                conversationId,
                ASSISTANT_TYPE,
                marker,
                confirmMarker
        );

        for (ChatMessage candidate : candidates) {
            String content = candidate.getContent();
            if (content == null) continue;
            // Enforce case-sensitive marker matching in Java regardless of DB collation behavior.
            if (!content.contains(marker) && !content.contains(confirmMarker)) continue;
            candidate.setContent(newContent);
            repository.save(candidate);
            return true;
        }
        return false;
    }

    /**
     * Resolves username for memory writes across servlet and Netty tool threads.
     * Shared fallback chain lives in UserContext.
     * Explicit username override remains highest priority for call-site correctness.
     */
    private String resolveCurrentUsername(String conversationId, String usernameOverride) {
        String username = userContext.resolveUsernameOrAnonymousPreferExplicit(usernameOverride, conversationId);
        if ("anonymous".equals(username)) {
            log.warn("resolveCurrentUsername: all resolution strategies failed for conversationId='{}', falling back to 'anonymous'", conversationId);
        }
        return username;
    }

    private Message mapToMessage(ChatMessage entity) {
        String content = entity.getContent();

        return switch (toMessageType(entity.getMessageType())) {
            case ASSISTANT -> new AssistantMessage(content);
            case SYSTEM -> new SystemMessage(content);
            default -> new UserMessage(content);
        };
    }

    private static MessageType toMessageType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return MessageType.USER;
        }
        return MESSAGE_TYPE_MAP.getOrDefault(rawType.toLowerCase(), MessageType.USER);
    }

    private void trimConversationIfNeeded(String conversationId, int addedMessageCount) {
        if (conversationId == null || conversationId.isBlank()) return;
        if (addedMessageCount <= 0) return;
        if (maxMessagesPerConversation <= 0) {
            if (trimDisabledWarningLogged.compareAndSet(false, true)) {
                log.warn("auto-trim disabled: app.chat.memory.max-messages-per-conversation={} (<= 0). chat_messages can grow unbounded",
                        maxMessagesPerConversation);
            }
            return;
        }

        long now = System.currentTimeMillis();
        maybeCleanupStaleTrimCounters(now);
        TrimCheckState state = getOrCreateTrimCheckState(conversationId, now);
        state.lock.lock();
        try {
            long touchedAt = System.currentTimeMillis();
            if (!shouldRunTrimCheckLocked(state, addedMessageCount, touchedAt)) return;

            try {
                requiresNewTx.executeWithoutResult(status -> {
                    int deleted = repository.trimConversationToLatest(conversationId, maxMessagesPerConversation);
                    if (deleted > 0 && log.isDebugEnabled()) {
                        log.debug("trimConversationIfNeeded: conversationId='{}' trimmed {} old messages, keepLatest={}",
                                conversationId, deleted, maxMessagesPerConversation);
                    }
                });
            } catch (Exception e) {
                log.warn("trimConversationIfNeeded: trim failed for conversationId='{}', messages were still saved: {}",
                        conversationId, e.getMessage());
            }
        } finally {
            state.lock.unlock();
        }
    }

    private TrimCheckState getOrCreateTrimCheckState(String conversationId, long now) {
        return trimCheckCounters.compute(conversationId, (id, existing) -> {
            if (existing == null) {
                return new TrimCheckState(now);
            }
            existing.lastTouchedAtMillis = now;
            return existing;
        });
    }

    private boolean shouldRunTrimCheckLocked(TrimCheckState state, int addedMessageCount, long now) {
        state.lastTouchedAtMillis = now;
        int updated = state.pendingMessages + addedMessageCount;
        boolean shouldTrim = updated >= TRIM_CHECK_INTERVAL;
        state.pendingMessages = shouldTrim ? (updated % TRIM_CHECK_INTERVAL) : updated;
        return shouldTrim;
    }

    private void maybeCleanupStaleTrimCounters(long now) {
        int tick = trimCounterCleanupTicker.incrementAndGet();
        if (tick % TRIM_COUNTER_CLEANUP_INTERVAL != 0) return;

        for (Map.Entry<String, TrimCheckState> entry : trimCheckCounters.entrySet()) {
            TrimCheckState state = entry.getValue();
            if (state == null) continue;
            if (now - state.lastTouchedAtMillis > TRIM_COUNTER_TTL_MILLIS) {
                trimCheckCounters.remove(entry.getKey(), state);
            }
        }
    }
}
