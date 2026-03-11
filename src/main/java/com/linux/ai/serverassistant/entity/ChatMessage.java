package com.linux.ai.serverassistant.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Data
@Table(
        name = ChatMessage.TABLE_NAME,
        indexes = {
                @Index(
                        name = "idx_chat_msg_conv_created_id",
                        columnList = "conversation_id, created_at, id"
                )
        }
)
public class ChatMessage {

    public static final String TABLE_NAME = "chat_messages";
    public static final String COL_ID = "id";
    public static final String COL_CONVERSATION_ID = "conversation_id";
    public static final String COL_USERNAME = "username";
    public static final String COL_MESSAGE_TYPE = "message_type";
    public static final String COL_CONTENT = "content";
    public static final String COL_CREATED_AT = "created_at";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = COL_ID)
    private Long id;

    @Column(name = COL_CONVERSATION_ID)
    private String conversationId; // Conversation Session ID (UUID)

    @Column(name = COL_USERNAME)
    private String username; // Associated user account

    @Column(name = COL_MESSAGE_TYPE)
    private String messageType; // USER, ASSISTANT, SYSTEM

    @Column(name = COL_CONTENT, columnDefinition = "TEXT")
    private String content;

    @Column(name = COL_CREATED_AT)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
