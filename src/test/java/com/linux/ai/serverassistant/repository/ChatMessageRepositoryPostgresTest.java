package com.linux.ai.serverassistant.repository;

import com.linux.ai.serverassistant.entity.ChatMessage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Tag("docker")
@Testcontainers(disabledWithoutDocker = true)
class ChatMessageRepositoryPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("serverassistant")
            .withUsername("serverassistant")
            .withPassword("serverassistant");

    @DynamicPropertySource
    static void configurePostgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", POSTGRES::getDriverClassName);
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private ChatMessageRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayMigrationShouldCreateChatMessagesSchema() {
        Integer versionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = TRUE AND version = '1'",
                Integer.class
        );
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_name = 'chat_messages'",
                Integer.class
        );
        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_chat_msg_conv_created_id'",
                Integer.class
        );

        assertEquals(1, versionCount);
        assertEquals(1, tableCount);
        assertEquals(1, indexCount);
    }

    @Test
    void deleteLastByConversationIdAndUsername_shouldDeleteScopedLatestRows() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 4, 10, 0, 0);

        ChatMessage aliceOld = message("c-del", "alice", "user", "alice-old", base);
        ChatMessage anonymousMsg = message("c-del", "anonymous", "user", "anonymous-mid", base.plusSeconds(1));
        ChatMessage bobMsg = message("c-del", "bob", "user", "bob-keep", base.plusSeconds(2));
        ChatMessage aliceNewest = message("c-del", "alice", "user", "alice-newest", base.plusSeconds(3));
        ChatMessage otherConversation = message("c-other", "alice", "user", "other-conv-keep", base.plusSeconds(4));

        repository.saveAll(List.of(
                aliceOld,
                anonymousMsg,
                bobMsg,
                aliceNewest,
                otherConversation
        ));
        repository.flush();

        int deleted = repository.deleteLastByConversationIdAndUsername("c-del", "alice", 3);
        repository.flush();

        assertEquals(3, deleted);
        List<String> remainingCdel = repository.findByConversationIdOrderByCreatedAtAsc("c-del")
                .stream()
                .map(ChatMessage::getContent)
                .toList();
        assertEquals(List.of("bob-keep"), remainingCdel);
    }

    @Test
    void trimConversationToLatest_shouldKeepNewestMessagesOnly() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 5, 11, 0, 0);

        ChatMessage m1 = message("c-trim", "alice", "user", "m1", base);
        ChatMessage m2 = message("c-trim", "alice", "user", "m2", base.plusSeconds(1));
        ChatMessage m3 = message("c-trim", "alice", "user", "m3", base.plusSeconds(2));
        ChatMessage m4 = message("c-trim", "alice", "user", "m4", base.plusSeconds(3));

        repository.saveAll(List.of(m1, m2, m3, m4));
        repository.flush();

        int deleted = repository.trimConversationToLatest("c-trim", 2);
        repository.flush();

        assertEquals(2, deleted);
        List<String> remainingTrim = repository.findByConversationIdOrderByCreatedAtAsc("c-trim")
                .stream()
                .map(ChatMessage::getContent)
                .toList();
        assertEquals(List.of("m3", "m4"), remainingTrim);
    }

    @Test
    void findLatestByConversationIdAndMessageTypeAndContentMarkers_shouldMatchMarkersCaseInsensitively() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 6, 12, 0, 0);

        ChatMessage markerHit = message(
                "c-marker",
                "alice",
                "ASSISTANT",
                "before [[[PENDING_COMMAND]]] after",
                base.plusSeconds(1)
        );
        ChatMessage confirmHit = message(
                "c-marker",
                "alice",
                "assistant",
                "contains [[[COMMAND_CONFIRMATION_REQUIRED]]]",
                base.plusSeconds(2)
        );
        ChatMessage miss = message("c-marker", "alice", "assistant", "plain content", base.plusSeconds(3));

        repository.saveAll(List.of(markerHit, confirmHit, miss));
        repository.flush();

        List<String> result = repository.findLatestByConversationIdAndMessageTypeAndContentMarkers(
                        "c-marker",
                        "assistant",
                        "[[[PENDING_COMMAND]]]",
                        "[[[COMMAND_CONFIRMATION_REQUIRED]]]"
                )
                .stream()
                .map(ChatMessage::getContent)
                .toList();

        assertEquals(
                List.of("contains [[[COMMAND_CONFIRMATION_REQUIRED]]]", "before [[[PENDING_COMMAND]]] after"),
                result
        );
    }

    private ChatMessage message(
            String conversationId,
            String username,
            String messageType,
            String content,
            LocalDateTime createdAt
    ) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setUsername(username);
        msg.setMessageType(messageType);
        msg.setContent(content);
        msg.setCreatedAt(createdAt);
        return msg;
    }
}
