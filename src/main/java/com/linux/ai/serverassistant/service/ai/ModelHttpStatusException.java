package com.linux.ai.serverassistant.service.ai;

/**
 * Typed model-stream exception that preserves the originating HTTP status code.
 */
class ModelHttpStatusException extends RuntimeException {

    private final int statusCode;
    private final Long retryAfterMillis;
    private final boolean retryable;

    ModelHttpStatusException(int statusCode, Throwable cause) {
        this(statusCode, cause, null, true);
    }

    ModelHttpStatusException(int statusCode, Throwable cause, Long retryAfterMillis) {
        this(statusCode, cause, retryAfterMillis, true);
    }

    ModelHttpStatusException(int statusCode, Throwable cause, Long retryAfterMillis, boolean retryable) {
        super("AI model HTTP status: " + statusCode, cause);
        this.statusCode = statusCode;
        this.retryAfterMillis = retryAfterMillis;
        this.retryable = retryable;
    }

    int getStatusCode() {
        return statusCode;
    }

    Long getRetryAfterMillis() {
        return retryAfterMillis;
    }

    boolean isRetryable() {
        return retryable;
    }
}
