package com.linux.ai.serverassistant.repository;

import com.linux.ai.serverassistant.entity.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
class ChatMessageRepositoryTest {

    @Autowired
    private ChatMessageRepository repository;

    @Test
    void findByConversationIdAndMessageTypeIgnoreCaseOrderByCreatedAtDescIdDesc_shouldBeStable() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

        ChatMessage older1 = assistant("c1", "older-1", base);
        ChatMessage older2 = assistant("c1", "older-2", base);
        ChatMessage newer = assistant("c1", "newer", base.plusSeconds(1));
        ChatMessage ignoredUser = user("c1", "not-assistant", base.plusSeconds(2));

        repository.saveAll(List.of(older1, older2, newer, ignoredUser));
        repository.flush();

        Page<ChatMessage> page = repository.findByConversationIdAndMessageTypeIgnoreCaseOrderByCreatedAtDescIdDesc(
                "c1",
                "ASSISTANT",
                PageRequest.of(0, 10)
        );

        List<String> contents = page.stream().map(ChatMessage::getContent).toList();
        assertEquals(List.of("newer", "older-2", "older-1"), contents);
    }

    @Test
    void findTop1ByConversationIdOrderByCreatedAtDescIdDesc_shouldUseIdTieBreaker() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 1, 2, 8, 30, 0);

        ChatMessage first = assistant("c2", "first", sameTime);
        ChatMessage second = assistant("c2", "second", sameTime);

        repository.save(first);
        repository.save(second);
        repository.flush();

        Optional<ChatMessage> latest = repository.findTop1ByConversationIdOrderByCreatedAtDescIdDesc("c2");

        assertTrue(latest.isPresent());
        assertEquals("second", latest.get().getContent());
    }

    @Test
    void findByConversationIdOrderByCreatedAtDescIdDesc_shouldUseStableSort() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 1, 3, 9, 0, 0);

        ChatMessage first = user("c3", "first", sameTime);
        ChatMessage second = user("c3", "second", sameTime);
        ChatMessage third = user("c3", "third", sameTime);

        repository.saveAll(List.of(first, second, third));
        repository.flush();

        List<String> ordered = repository.findByConversationIdOrderByCreatedAtDescIdDesc("c3", PageRequest.of(0, 10))
                .stream()
                .map(ChatMessage::getContent)
                .toList();

        assertEquals(List.of("third", "second", "first"), ordered);
    }

    @Test
    void findHistoryPageByCursor_shouldUseCreatedAtThenIdSeekOrder() {
        LocalDateTime sameTime = LocalDateTime.of(2026, 1, 6, 9, 0, 0);

        ChatMessage oldest = user("c-cursor", "oldest", sameTime.minusSeconds(1));
        ChatMessage first = user("c-cursor", "first", sameTime);
        ChatMessage second = user("c-cursor", "second", sameTime);
        ChatMessage cursor = user("c-cursor", "cursor", sameTime);
        ChatMessage otherConversation = user("c-other", "other", sameTime);

        repository.saveAll(List.of(oldest, first, second, cursor, otherConversation));
        repository.flush();

        List<String> ordered = repository.findHistoryPageByCursor(
                        "c-cursor",
                        cursor.getCreatedAt(),
                        cursor.getId(),
                        PageRequest.of(0, 10)
                ).stream()
                .map(ChatMessage::getContent)
                .toList();

        assertEquals(List.of("second", "first", "oldest"), ordered);
    }

    @Test
    void deleteLastByConversationIdAndUsername_shouldDeleteScopedLatestRows() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 4, 10, 0, 0);

        ChatMessage aliceOld = scopedUser("c-del", "alice", "alice-old", base);
        ChatMessage anonymousMsg = scopedUser("c-del", "anonymous", "anonymous-mid", base.plusSeconds(1));
        ChatMessage nullOwnerMsg = scopedUser("c-del", null, "null-owner-mid", base.plusSeconds(2));
        ChatMessage bobMsg = scopedUser("c-del", "bob", "bob-keep", base.plusSeconds(3));
        ChatMessage aliceNewest = scopedUser("c-del", "alice", "alice-newest", base.plusSeconds(4));
        ChatMessage otherConversation = scopedUser("c-other", "alice", "other-conv-keep", base.plusSeconds(5));

        repository.saveAll(List.of(
                aliceOld,
                anonymousMsg,
                nullOwnerMsg,
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
        assertEquals(List.of("alice-old", "bob-keep"), remainingCdel);

        List<String> remainingOther = repository.findByConversationIdOrderByCreatedAtAsc("c-other")
                .stream()
                .map(ChatMessage::getContent)
                .toList();
        assertEquals(List.of("other-conv-keep"), remainingOther);
    }

    @Test
    void trimConversationToLatest_shouldKeepNewestMessagesOnly() {
        LocalDateTime base = LocalDateTime.of(2026, 1, 5, 11, 0, 0);

        ChatMessage m1 = user("c-trim", "m1", base);
        ChatMessage m2 = user("c-trim", "m2", base.plusSeconds(1));
        ChatMessage m3 = user("c-trim", "m3", base.plusSeconds(2));
        ChatMessage m4 = user("c-trim", "m4", base.plusSeconds(3));
        ChatMessage other = user("c-other", "other", base.plusSeconds(4));

        repository.saveAll(List.of(m1, m2, m3, m4, other));
        repository.flush();

        int deleted = repository.trimConversationToLatest("c-trim", 2);
        repository.flush();

        assertEquals(2, deleted);
        List<String> remainingTrim = repository.findByConversationIdOrderByCreatedAtAsc("c-trim")
                .stream()
                .map(ChatMessage::getContent)
                .toList();
        assertEquals(List.of("m3", "m4"), remainingTrim);

        List<String> remainingOther = repository.findByConversationIdOrderByCreatedAtAsc("c-other")
                .stream()
                .map(ChatMessage::getContent)
                .toList();
        assertEquals(List.of("other"), remainingOther);
    }

    private ChatMessage assistant(String conversationId, String content, LocalDateTime createdAt) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setMessageType("assistant");
        msg.setContent(content);
        msg.setCreatedAt(createdAt);
        return msg;
    }

    private ChatMessage user(String conversationId, String content, LocalDateTime createdAt) {
        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conversationId);
        msg.setMessageType("user");
        msg.setContent(content);
        msg.setCreatedAt(createdAt);
        return msg;
    }

    private ChatMessage scopedUser(String conversationId, String username, String content, LocalDateTime createdAt) {
        ChatMessage msg = user(conversationId, content, createdAt);
        msg.setUsername(username);
        return msg;
    }
}
