package com.linux.ai.serverassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ChatStreamRequest {
    public static final int MAX_MESSAGE_LENGTH = 8_000;
    private static final String UUID_REGEX =
            "^(\\s*|\\s*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\s*)$";

    @NotBlank(message = "message 不能為空")
    @Size(max = MAX_MESSAGE_LENGTH, message = "message 長度不可超過 " + MAX_MESSAGE_LENGTH)
    private String message;

    @Pattern(regexp = UUID_REGEX, message = "conversationId 必須為 UUID 格式")
    @Size(max = 128, message = "conversationId 長度不可超過 128")
    private String conversationId;

    @Size(max = 64, message = "model 長度不可超過 64")
    private String model;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
