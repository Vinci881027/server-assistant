package com.linux.ai.serverassistant.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializePasswordAsCharArray() throws Exception {
        String json = """
                {
                  "username": "alice",
                  "password": "s3cret!"
                }
                """;

        LoginRequest request = objectMapper.readValue(json, LoginRequest.class);

        assertEquals("alice", request.getUsername());
        assertArrayEquals("s3cret!".toCharArray(), request.getPassword());
    }

    @Test
    void shouldRejectNonStringPassword() {
        String json = """
                {
                  "username": "alice",
                  "password": 12345
                }
                """;

        assertThrows(Exception.class, () -> objectMapper.readValue(json, LoginRequest.class));
    }
}
