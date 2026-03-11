package com.linux.ai.serverassistant.exception;

/**
 * Thrown when a user exceeds the per-user chat request rate limit.
 */
public class RateLimitExceededException extends RuntimeException {

    public static final String REASON_USER_RATE_LIMIT = "user_rate_limit";
    public static final String REASON_USER_TPM_LIMIT = "user_tpm_limit";
    public static final String REASON_GLOBAL_TPM_LIMIT = "global_tpm_limit";

    private final long retryAfterSeconds;
    private final String reason;

    public RateLimitExceededException(long retryAfterSeconds, String reason) {
        super("請求過於頻繁，請稍後再試。");
        this.retryAfterSeconds = retryAfterSeconds;
        this.reason = normalizeReason(reason);
    }

    public static RateLimitExceededException userRateLimit(long retryAfterSeconds) {
        return new RateLimitExceededException(retryAfterSeconds, REASON_USER_RATE_LIMIT);
    }

    public static RateLimitExceededException globalTpmLimit(long retryAfterSeconds) {
        return new RateLimitExceededException(retryAfterSeconds, REASON_GLOBAL_TPM_LIMIT);
    }

    public static RateLimitExceededException userTpmLimit(long retryAfterSeconds) {
        return new RateLimitExceededException(retryAfterSeconds, REASON_USER_TPM_LIMIT);
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }

    public String getReason() {
        return reason;
    }

    private static String normalizeReason(String reason) {
        if (REASON_GLOBAL_TPM_LIMIT.equals(reason)) return REASON_GLOBAL_TPM_LIMIT;
        if (REASON_USER_TPM_LIMIT.equals(reason)) return REASON_USER_TPM_LIMIT;
        return REASON_USER_RATE_LIMIT;
    }
}
