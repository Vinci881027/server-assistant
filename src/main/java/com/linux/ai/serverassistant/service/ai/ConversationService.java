package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.entity.ChatMessage;
import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.service.JpaChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

/**
 * Conversation Service
 *
 * Provides conversation history management capabilities:
 * - Conversation ID generation and validation
 * - Conversation list retrieval with titles
 * - History retrieval with pagination
 * - Memory clearing
 * - Username assignment for conversations
 *
 * @author Claude Code - Phase 2 Refactoring
 * @version 1.0
 */
@Service
public class ConversationService {

    // ========== Dependencies ==========

    private final JpaChatMemory jpaChatMemory;
    private final ChatMessageRepository chatMessageRepository;

    /**
     * Default page size for history display.
     */
    static final int DEFAULT_HISTORY_PAGE_SIZE = 50;

    /**
     * Maximum title length for conversation display.
     */
    private static final int MAX_TITLE_LENGTH = 20;

    /**
     * Maximum conversations returned to the sidebar — prevents memory exhaustion
     * when a user has thousands of conversations.
     */
    private static final int MAX_CONVERSATIONS = 100;

    // ========== Constructor ==========

    /**
     * Constructs the ConversationService with required dependencies.
     *
     * @param jpaChatMemory the chat memory store
     * @param chatMessageRepository repository for chat message persistence
     */
    public ConversationService(JpaChatMemory jpaChatMemory,
                               ChatMessageRepository chatMessageRepository) {
        this.jpaChatMemory = jpaChatMemory;
        this.chatMessageRepository = chatMessageRepository;
    }

    // ========== Public API - Conversation ID Management ==========

    /**
     * Gets or generates a conversation ID.
     *
     * If the provided conversationId is null or empty, generates a new UUID.
     * Otherwise, returns the provided conversationId.
     *
     * @param conversationId the provided conversation ID (may be null)
     * @return a valid conversation ID
     */
    public String getOrGenerateConversationId(String conversationId) {
        if (conversationId == null || conversationId.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return conversationId;
    }

    /**
     * Resolves a conversation ID for streaming and enforces ownership in one transaction.
     *
     * Rules:
     * - null/blank conversationId: generate new UUID
     * - existing conversation owned by caller: allowed
     * - non-existent conversationId: allowed
     * - conversationId owned by another user: denied
     *
     * @param conversationId requested conversation ID (nullable)
     * @param username authenticated username
     * @return resolved conversation ID (existing or newly generated)
     */
    @Transactional(isolation = Isolation.SERIALIZABLE, readOnly = true)
    public String resolveConversationIdForStream(String conversationId, String username) {
        String trimmedConversationId = conversationId == null ? null : conversationId.trim();
        if (trimmedConversationId == null || trimmedConversationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (username == null || username.isBlank()) {
            throw new AccessDeniedException("無權存取該對話");
        }
        if (chatMessageRepository.existsByConversationIdAndUsername(trimmedConversationId, username)) {
            return trimmedConversationId;
        }
        if (chatMessageRepository.existsByConversationId(trimmedConversationId)) {
            throw new AccessDeniedException("無權存取該對話");
        }
        return trimmedConversationId;
    }

    // ========== Public API - Conversation List ==========

    /**
     * Retrieves all conversations for a specific user.
     *
     * Returns a list of conversation summaries with ID and title.
     * Titles are truncated to MAX_TITLE_LENGTH characters.
     *
     * @param username the username to retrieve conversations for
     * @return list of conversation maps with "id" and "title" keys
     */
    public List<Map<String, Object>> getConversationsForUser(String username) {
        if (username == null) {
            return List.of();
        }

        return chatMessageRepository.findConversationSummariesByUsername(username,
                        PageRequest.of(0, MAX_CONVERSATIONS)).stream()
                .map(row -> {
                    String id = (String) row[0];
                    String content = (String) row[1];
                    String title = truncateTitle(content);
                    LocalDateTime updatedAt = (LocalDateTime) row[2];
                    Long messageCount = (Long) row[3];
                    Map<String, Object> map = new HashMap<>();
                    map.put("id", id);
                    map.put("title", title);
                    map.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
                    map.put("messageCount", messageCount != null ? messageCount : 0L);
                    return map;
                })
                .toList();
    }

    /**
     * Validates whether a user can use the provided conversation ID.
     *
     * Rules:
     * - null/blank conversationId is always allowed (a new ID will be generated later)
     * - if conversationId already belongs to this user -> allowed
     * - if conversationId does not exist yet -> allowed
     * - if conversationId exists but belongs to another user -> denied
     *
     * REPEATABLE_READ ensures both DB reads see a consistent snapshot, preventing
     * two concurrent callers from both seeing a non-existent ID and claiming it.
     */
    @Transactional(isolation = Isolation.SERIALIZABLE, readOnly = true)
    public boolean canUseConversationId(String conversationId, String username) {
        if (conversationId == null || conversationId.isBlank()) {
            return true;
        }
        if (username == null || username.isBlank()) {
            return false;
        }
        if (chatMessageRepository.existsByConversationIdAndUsername(conversationId, username)) {
            return true;
        }
        return !chatMessageRepository.existsByConversationId(conversationId);
    }

    /**
     * Strict ownership check.
     *
     * Unlike {@link #canUseConversationId(String, String)}, this only returns true when
     * the conversation already exists and belongs to the given user.
     */
    public boolean isConversationOwnedByUser(String conversationId, String username) {
        if (conversationId == null || conversationId.isBlank()) return false;
        if (username == null || username.isBlank()) return false;
        return chatMessageRepository.existsByConversationIdAndUsername(conversationId, username);
    }

    /**
     * Estimates total history size in characters for token budgeting.
     *
     * Returns 0 when conversation is absent, inaccessible, or empty.
     */
    @Transactional(readOnly = true)
    public long estimateConversationHistoryChars(String conversationId, String username) {
        if (conversationId == null || conversationId.isBlank()) {
            return 0L;
        }
        if (!isConversationOwnedByUser(conversationId, username)) {
            return 0L;
        }
        Long totalChars = chatMessageRepository.sumContentLengthByConversationId(conversationId);
        return totalChars == null ? 0L : Math.max(0L, totalChars);
    }

    // ========== Public API - History Management ==========

    /**
     * Retrieves a page of conversation history for display.
     *
     * Returns up to {@code limit} messages, skipping the {@code offset} most-recent ones.
     * Each message includes "role" (user or ai), "content", and "createdAt" (ISO-8601 local datetime).
     * The response map also contains "total" (total message count) so the caller
     * can determine whether more pages exist.
     *
     * @param conversationId the conversation ID
     * @param username       the authenticated username (ownership check)
     * @param limit          max messages to return (caller should clamp to a safe max)
     * @param offset         how many most-recent messages to skip (0 = most recent page)
     * @return map with "messages" (List), "total" (long), "offset" (int), "limit" (int)
     */
    public Map<String, Object> getRecentHistory(String conversationId, String username, int limit, int offset) {
        if (!isConversationOwnedByUser(conversationId, username)) {
            return Map.of("messages", List.of(), "total", 0L, "offset", offset, "limit", limit);
        }
        if (offset % limit != 0) {
            throw new IllegalArgumentException("offset must be a multiple of limit");
        }

        long total = chatMessageRepository.countByConversationId(conversationId);
        int page = offset / limit;
        List<ChatMessage> latestDesc = new ArrayList<>(
                chatMessageRepository.findByConversationIdOrderByCreatedAtDescIdDesc(
                        conversationId,
                        PageRequest.of(page, limit))
        );
        Collections.reverse(latestDesc);
        List<Map<String, Object>> messageDtos = toMessageDtos(latestDesc);

        return Map.of("messages", messageDtos, "total", total, "offset", offset, "limit", limit);
    }

    /**
     * Retrieves history with keyset pagination to avoid large OFFSET scans.
     *
     * @param conversationId conversation ID
     * @param username authenticated username
     * @param limit max messages to return
     * @param beforeCreatedAt cursor timestamp; null means newest page
     * @param beforeId cursor id; null means newest page
     * @return map with "messages", "total", "limit", and optional next cursor fields
     */
    public Map<String, Object> getRecentHistoryByCursor(
            String conversationId,
            String username,
            int limit,
            LocalDateTime beforeCreatedAt,
            Long beforeId) {
        if (!isConversationOwnedByUser(conversationId, username)) {
            return Map.of("messages", List.of(), "total", 0L, "limit", limit);
        }

        long total = chatMessageRepository.countByConversationId(conversationId);
        PageRequest pageRequest = PageRequest.of(0, limit);
        List<ChatMessage> latestDesc = new ArrayList<>(
                beforeCreatedAt == null
                        ? chatMessageRepository.findByConversationIdOrderByCreatedAtDescIdDesc(conversationId, pageRequest)
                        : chatMessageRepository.findHistoryPageByCursor(conversationId, beforeCreatedAt, beforeId, pageRequest)
        );
        LocalDateTime nextCursorCreatedAt = null;
        Long nextCursorId = null;
        if (!latestDesc.isEmpty()) {
            ChatMessage oldestInPage = latestDesc.get(latestDesc.size() - 1);
            nextCursorCreatedAt = oldestInPage.getCreatedAt();
            nextCursorId = oldestInPage.getId();
            Collections.reverse(latestDesc);
        }
        List<Map<String, Object>> messageDtos = toMessageDtos(latestDesc);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messages", messageDtos);
        response.put("total", total);
        response.put("limit", limit);
        if (nextCursorCreatedAt != null && nextCursorId != null) {
            response.put("nextCursorCreatedAt", nextCursorCreatedAt.toString());
            response.put("nextCursorId", nextCursorId);
        }
        return response;
    }

    /**
     * Clears the conversation history for a specific conversation.
     * Verifies that the conversation belongs to the authenticated user.
     *
     * @param conversationId the conversation ID to clear
     * @param username the authenticated username
     */
    public void clearHistory(String conversationId, String username) {
        if (!isConversationOwnedByUser(conversationId, username)) {
            return;
        }
        jpaChatMemory.clear(conversationId);
    }

    /**
     * Deletes the last {@code count} messages from a conversation.
     * Used by the regenerate flow to remove the stale user+AI exchange before resending.
     *
     * @param conversationId the conversation ID
     * @param count number of messages to delete from the end
     * @param username the authenticated username (ownership check)
     */
    public void deleteLastMessages(String conversationId, int count, String username) {
        if (!isConversationOwnedByUser(conversationId, username)) {
            return;
        }
        jpaChatMemory.deleteLastMessages(conversationId, count, username);
    }

    // ========== Public API - Username Assignment ==========

    /**
     * Assigns a username to all messages in a conversation.
     *
     * This is used to ensure conversation history is properly attributed
     * to the authenticated user, even in async contexts where session may be lost.
     *
     * @param conversationId the conversation ID
     * @param username the username to assign
     */
    @Transactional
    public void assignUsernameToConversation(String conversationId, String username) {
        if (username != null) {
            chatMessageRepository.updateUsernameByConversationId(conversationId, username);
        }
    }

    /**
     * Persists deterministic route exchange in one synchronous transaction.
     *
     * This keeps DB writes out of reactive stream transactions while ensuring
     * user/assistant messages and username assignment are committed atomically.
     */
    @Transactional
    public void persistDeterministicExchange(
            String conversationId,
            String userMessage,
            String assistantResponse,
            String username) {
        jpaChatMemory.add(conversationId, List.of(
                new UserMessage(userMessage),
                new AssistantMessage(assistantResponse)
        ), username);
        assignUsernameToConversation(conversationId, username);
    }

    // ========== Private Helper Methods ==========

    /**
     * Truncates a title to MAX_TITLE_LENGTH characters.
     *
     * @param content the original content
     * @return truncated title with "..." suffix if truncated
     */
    private String truncateTitle(String content) {
        if (content == null || content.isEmpty()) {
            return "新對話";
        }

        if (content.length() > MAX_TITLE_LENGTH) {
            return content.substring(0, MAX_TITLE_LENGTH) + "...";
        }

        return content;
    }

    /**
     * Determines the role of a message (user or ai).
     *
     * @param msg the message
     * @return "user" or "ai"
     */
    private String determineMessageRole(ChatMessage msg) {
        String messageType = msg.getMessageType();
        if (messageType == null || messageType.isBlank()) {
            return "user";
        }
        if ("USER".equalsIgnoreCase(messageType)) {
            return "user";
        }
        if ("ASSISTANT".equalsIgnoreCase(messageType) || "SYSTEM".equalsIgnoreCase(messageType)) {
            return "ai";
        }
        // Keep previous fallback behavior from JpaChatMemory mapping (unknown -> user).
        // This avoids re-labeling legacy or malformed records as AI.
        return "user";
    }

    private List<Map<String, Object>> toMessageDtos(List<ChatMessage> messages) {
        return messages.stream()
                .map(msg -> {
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("role", determineMessageRole(msg));
                    dto.put("content", msg.getContent() == null ? "" : msg.getContent());
                    dto.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : null);
                    return dto;
                })
                .toList();
    }
}
