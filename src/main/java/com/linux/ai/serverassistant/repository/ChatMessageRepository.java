package com.linux.ai.serverassistant.repository;

import com.linux.ai.serverassistant.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    // Find by conversation ID and sort by creation time ascending
    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    List<ChatMessage> findByConversationIdOrderByCreatedAtDescIdDesc(String conversationId, Pageable pageable);

    @Query("""
            SELECT c
            FROM ChatMessage c
            WHERE c.conversationId = :conversationId
              AND (c.createdAt < :beforeCreatedAt
                 OR (c.createdAt = :beforeCreatedAt AND c.id < :beforeId))
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<ChatMessage> findHistoryPageByCursor(
            String conversationId,
            LocalDateTime beforeCreatedAt,
            Long beforeId,
            Pageable pageable
    );

    Optional<ChatMessage> findTop1ByConversationIdOrderByCreatedAtDescIdDesc(String conversationId);

    Page<ChatMessage> findByConversationIdAndMessageTypeIgnoreCaseOrderByCreatedAtDescIdDesc(
            String conversationId,
            String messageType,
            Pageable pageable
    );

    @Query("""
            SELECT c
            FROM ChatMessage c
            WHERE c.conversationId = :conversationId
              AND LOWER(c.messageType) = LOWER(:messageType)
              AND c.content IS NOT NULL
              AND (
                    LOCATE(:marker, CAST(c.content AS string)) > 0
                 OR LOCATE(:confirmMarker, CAST(c.content AS string)) > 0
              )
            ORDER BY c.createdAt DESC, c.id DESC
            """)
    List<ChatMessage> findLatestByConversationIdAndMessageTypeAndContentMarkers(
            String conversationId,
            String messageType,
            String marker,
            String confirmMarker
    );

    // Find all chat records by username (for Admin use)
    List<ChatMessage> findByUsernameOrderByCreatedAtAsc(String username);

    Page<ChatMessage> findByUsernameOrderByCreatedAtAsc(String username, Pageable pageable);

    Page<ChatMessage> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    void deleteByConversationId(String conversationId);

    @Query("SELECT DISTINCT c.username FROM ChatMessage c")
    List<String> findDistinctUsernames();

    // Find all conversation IDs for a specific user (for Sidebar use)
    @Query("SELECT DISTINCT c.conversationId FROM ChatMessage c WHERE c.username = :username")
    List<String> findConversationIdsByUsername(String username);

    // Find all conversation IDs and titles for a specific user (using the content of the first message)
    @Query("SELECT c.conversationId, c.content FROM ChatMessage c WHERE c.username = :username AND c.id IN (SELECT MIN(m.id) FROM ChatMessage m WHERE m.username = :username GROUP BY m.conversationId) ORDER BY c.id DESC")
    List<Object[]> findConversationTitlesByUsername(String username);

    // Pageable variant — used to cap the number of conversations returned to the sidebar
    @Query("SELECT c.conversationId, c.content FROM ChatMessage c WHERE c.username = :username AND c.id IN (SELECT MIN(m.id) FROM ChatMessage m WHERE m.username = :username GROUP BY m.conversationId) ORDER BY c.id DESC")
    List<Object[]> findConversationTitlesByUsername(String username, Pageable pageable);

    // Returns conversationId, first message content (title), last updated time, message count — ordered by most recently active
    @Query("""
            SELECT c.conversationId, c.content, MAX(m.createdAt), COUNT(m.id)
            FROM ChatMessage c
            JOIN ChatMessage m ON m.conversationId = c.conversationId
            WHERE c.username = :username
              AND c.id IN (
                SELECT MIN(mm.id) FROM ChatMessage mm
                WHERE mm.username = :username
                GROUP BY mm.conversationId
              )
            GROUP BY c.conversationId, c.content
            ORDER BY MAX(m.createdAt) DESC
            """)
    List<Object[]> findConversationSummariesByUsername(String username, Pageable pageable);

    @Modifying
    @Transactional
    @Query("UPDATE ChatMessage c SET c.username = :username WHERE c.conversationId = :conversationId AND (c.username IS NULL OR c.username = 'anonymous')")
    void updateUsernameByConversationId(String conversationId, String username);

    boolean existsByConversationIdAndUsername(String conversationId, String username);
    boolean existsByConversationId(String conversationId);

    long countByUsername(String username);

    long countByConversationId(String conversationId);

    @Query("""
            SELECT COALESCE(SUM(LENGTH(c.content)), 0)
            FROM ChatMessage c
            WHERE c.conversationId = :conversationId
            """)
    Long sumContentLengthByConversationId(String conversationId);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage c WHERE c.username = :username")
    int deleteAllByUsername(String username);

    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage c WHERE c.createdAt < :cutoff")
    int deleteByCreatedAtBefore(LocalDateTime cutoff);

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM chat_messages
            WHERE id IN (
                SELECT id
                FROM (
                    SELECT id
                    FROM chat_messages
                    WHERE conversation_id = :conversationId
                      AND (username = :username OR username = 'anonymous' OR username IS NULL)
                    ORDER BY created_at DESC, id DESC
                    LIMIT :count
                ) limited_ids
            )
            """, nativeQuery = true)
    int deleteLastByConversationIdAndUsername(String conversationId, String username, int count);

    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM chat_messages
            WHERE conversation_id = :conversationId
              AND id NOT IN (
                SELECT id
                FROM (
                  SELECT id
                  FROM chat_messages
                  WHERE conversation_id = :conversationId
                  ORDER BY created_at DESC, id DESC
                  LIMIT :keepLatest
                ) keep_ids
              )
            """, nativeQuery = true)
    int trimConversationToLatest(String conversationId, int keepLatest);
}
