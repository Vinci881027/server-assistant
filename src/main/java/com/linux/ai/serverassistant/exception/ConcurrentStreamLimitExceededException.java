package com.linux.ai.serverassistant.exception;

/**
 * Thrown when a user exceeds the per-user concurrent chat stream limit.
 */
public class ConcurrentStreamLimitExceededException extends RuntimeException {

    private final int maxConcurrentStreams;
    private final long retryAfterSeconds;

    public ConcurrentStreamLimitExceededException(int maxConcurrentStreams) {
        this(maxConcurrentStreams, 1L);
    }

    public ConcurrentStreamLimitExceededException(int maxConcurrentStreams, long retryAfterSeconds) {
        super("您已有對話進行中，請等待完成。");
        this.maxConcurrentStreams = maxConcurrentStreams;
        this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
    }

    public int getMaxConcurrentStreams() {
        return maxConcurrentStreams;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
