package com.linux.ai.serverassistant.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public class LoginRequest {
    private String username;
    @JsonDeserialize(using = PasswordCharArrayDeserializer.class)
    private char[] password;

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    @JsonIgnore
    public char[] getPassword() { return password; }
    public void setPassword(char[] password) { this.password = password; }

    @Override
    public String toString() {
        return "LoginRequest{username='" + username + "', password=[REDACTED]}";
    }
}
