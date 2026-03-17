package com.linux.ai.serverassistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class ConfirmCommandRequest {
    private static final String UUID_REGEX =
            "^\\s*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\s*$";

    @NotBlank(message = "command 不能為空")
    @Size(max = 8192, message = "command 長度不可超過 8192")
    private String command;

    @Pattern(regexp = UUID_REGEX, message = "conversationId 必須為 UUID 格式")
    @Size(max = 128, message = "conversationId 長度不可超過 128")
    private String conversationId;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
