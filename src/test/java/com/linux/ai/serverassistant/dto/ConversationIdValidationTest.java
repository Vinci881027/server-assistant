package com.linux.ai.serverassistant.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationIdValidationTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void chatStreamRequest_shouldRejectNonUuidConversationId() {
        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("hello");
        request.setConversationId("not-a-uuid");

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        assertTrue(hasConversationIdViolation(violations));
    }

    @Test
    void chatStreamRequest_shouldAllowUuidConversationId() {
        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("hello");
        request.setConversationId("123e4567-e89b-12d3-a456-426614174000");
        request.setModel("20b");

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        assertFalse(hasConversationIdViolation(violations));
    }

    @Test
    void chatStreamRequest_shouldRejectBlankConversationId() {
        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("hello");
        request.setConversationId("   ");
        request.setModel("20b");

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        assertTrue(hasConversationIdViolation(violations));
    }

    @Test
    void chatStreamRequest_shouldRejectBlankModel() {
        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("hello");
        request.setConversationId("123e4567-e89b-12d3-a456-426614174000");
        request.setModel("   ");

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        assertTrue(hasPropertyViolation(violations, "model"));
    }

    @Test
    void chatStreamRequest_shouldRejectInvalidModelKey() {
        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("hello");
        request.setConversationId("123e4567-e89b-12d3-a456-426614174000");
        request.setModel("gpt/oss-20b");

        Set<ConstraintViolation<ChatStreamRequest>> violations = validator.validate(request);

        assertTrue(hasPropertyViolation(violations, "model"));
    }

    @Test
    void confirmCommandRequest_shouldRejectNonUuidConversationId() {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("ls -la");
        request.setConversationId("abc");

        Set<ConstraintViolation<ConfirmCommandRequest>> violations = validator.validate(request);

        assertTrue(hasConversationIdViolation(violations));
    }

    @Test
    void confirmCommandRequest_shouldAllowUuidConversationId() {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("ls -la");
        request.setConversationId("123e4567-e89b-12d3-a456-426614174000");

        Set<ConstraintViolation<ConfirmCommandRequest>> violations = validator.validate(request);

        assertFalse(hasConversationIdViolation(violations));
    }

    @Test
    void confirmCommandRequest_shouldAllowUuidWithSurroundingSpaces() {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("ls -la");
        request.setConversationId(" 123e4567-e89b-12d3-a456-426614174000 ");

        Set<ConstraintViolation<ConfirmCommandRequest>> violations = validator.validate(request);

        assertFalse(hasConversationIdViolation(violations));
    }

    @Test
    void confirmCommandRequest_shouldRejectBlankConversationId() {
        ConfirmCommandRequest request = new ConfirmCommandRequest();
        request.setCommand("ls -la");
        request.setConversationId("  ");

        Set<ConstraintViolation<ConfirmCommandRequest>> violations = validator.validate(request);

        assertTrue(hasConversationIdViolation(violations));
    }

    @Test
    void cancelCommandRequest_shouldRejectNonUuidConversationId() {
        CancelCommandRequest request = new CancelCommandRequest();
        request.setConversationId("invalid-conversation-id");

        Set<ConstraintViolation<CancelCommandRequest>> violations = validator.validate(request);

        assertTrue(hasConversationIdViolation(violations));
    }

    @Test
    void cancelCommandRequest_shouldAllowBlankConversationId() {
        CancelCommandRequest request = new CancelCommandRequest();
        request.setConversationId("");

        Set<ConstraintViolation<CancelCommandRequest>> violations = validator.validate(request);

        assertFalse(hasConversationIdViolation(violations));
    }

    @Test
    void cancelCommandRequest_shouldAllowWhitespaceConversationId() {
        CancelCommandRequest request = new CancelCommandRequest();
        request.setConversationId("   ");

        Set<ConstraintViolation<CancelCommandRequest>> violations = validator.validate(request);

        assertFalse(hasConversationIdViolation(violations));
    }

    private static boolean hasConversationIdViolation(Set<? extends ConstraintViolation<?>> violations) {
        return hasPropertyViolation(violations, "conversationId");
    }

    private static boolean hasPropertyViolation(Set<? extends ConstraintViolation<?>> violations, String propertyName) {
        return violations.stream()
                .anyMatch(v -> propertyName.equals(v.getPropertyPath().toString()));
    }
}
