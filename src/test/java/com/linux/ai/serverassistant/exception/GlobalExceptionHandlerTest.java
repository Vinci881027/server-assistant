package com.linux.ai.serverassistant.exception;

import com.linux.ai.serverassistant.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleConcurrentStreamLimitExceededException_shouldReturnErrorCodeDataAndRetryAfterHeader() {
        ConcurrentStreamLimitExceededException ex = new ConcurrentStreamLimitExceededException(2, 11);

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                handler.handleConcurrentStreamLimitExceededException(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("11", response.getHeaders().getFirst("Retry-After"));

        ApiResponse<Map<String, Object>> body = response.getBody();
        assertNotNull(body);
        assertFalse(body.isSuccess());
        assertNotNull(body.getError());
        assertEquals("CONCURRENT_STREAM_LIMIT_EXCEEDED", body.getError().getCode());
        assertNotNull(body.getData());
        assertEquals(2, body.getData().get("maxConcurrentStreams"));
        assertEquals(11L, body.getData().get("retryAfterSeconds"));
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) body.getData().get("details");
        assertNotNull(details);
        assertEquals(11L, details.get("retryAfterSeconds"));
    }

    @Test
    void handleRateLimitExceededException_shouldKeepRetryAfterHeaderAndExposeRetryAfterDetails() {
        RateLimitExceededException ex = RateLimitExceededException.globalTpmLimit(7);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleRateLimitExceededException(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("7", response.getHeaders().getFirst("Retry-After"));
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getError());
        assertEquals("RATE_LIMIT_EXCEEDED", response.getBody().getError().getCode());
        assertNotNull(response.getBody().getData());
        assertEquals("global_tpm_limit", response.getBody().getData().get("reason"));
        assertEquals(7L, ((Number) response.getBody().getData().get("retryAfterSeconds")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) response.getBody().getData().get("details");
        assertNotNull(details);
        assertEquals(7L, ((Number) details.get("retryAfterSeconds")).longValue());
    }

    @Test
    void handleRateLimitExceededException_shouldExposeUserTpmReason() {
        RateLimitExceededException ex = RateLimitExceededException.userTpmLimit(9);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleRateLimitExceededException(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("9", response.getHeaders().getFirst("Retry-After"));
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getData());
        assertEquals("user_tpm_limit", response.getBody().getData().get("reason"));
    }

    @Test
    void handleRateLimitExceededException_shouldNormalizeRetryAfterToMinimumOneSecond() {
        RateLimitExceededException ex = RateLimitExceededException.userRateLimit(0);

        ResponseEntity<ApiResponse<Map<String, Object>>> response = handler.handleRateLimitExceededException(ex);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
        assertNotNull(response.getBody());
        assertNotNull(response.getBody().getData());
        assertEquals(1L, ((Number) response.getBody().getData().get("retryAfterSeconds")).longValue());
    }
}
