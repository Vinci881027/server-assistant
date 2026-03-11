package com.linux.ai.serverassistant.service.security;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/**
 * Global per-model token bucket for TPM throttling.
 *
 * Capacity defaults to model TPM and can be dynamically reconfigured per model.
 * Refill rate is capacity per minute.
 */
@Service
public class TpmBucket {

    private static final long ONE_MINUTE_MS = 60_000L;

    private final Clock clock;
    private final Map<String, BucketState> buckets = new HashMap<>();
    private final Map<String, Integer> configuredCapacities = new HashMap<>();
    private final Object lock = new Object();

    public TpmBucket() {
        this(Clock.systemUTC());
    }

    TpmBucket(Clock clock) {
        this.clock = clock;
    }

    /**
     * Updates the bucket capacity for a model.
     *
     * @param modelKey model bucket key
     * @param capacityTokens new capacity in tokens (clamped to at least 1)
     */
    public void reconfigure(String modelKey, long capacityTokens) {
        if (modelKey == null || modelKey.isBlank()) {
            return;
        }
        int normalizedCapacity = normalizeCapacity(capacityTokens);
        long nowMs = clock.millis();
        synchronized (lock) {
            configuredCapacities.put(modelKey, normalizedCapacity);
            BucketState state = buckets.get(modelKey);
            if (state != null) {
                refill(state, nowMs);
                state.reconfigure(normalizedCapacity);
            }
        }
    }

    /**
     * Clears all model bucket states and configured capacities.
     */
    public void clear() {
        synchronized (lock) {
            buckets.clear();
            configuredCapacities.clear();
        }
    }

    /**
     * @param modelKey bucket key (typically AI model key)
     * @param tpmTokens configured model TPM (fallback capacity before explicit reconfigure)
     * @param requestedTokens estimated token cost for this request
     * @return 0 if allowed; otherwise retry-after seconds
     */
    public long tryConsume(String modelKey, int tpmTokens, long requestedTokens) {
        return consume(modelKey, tpmTokens, requestedTokens);
    }

    /**
     * Checks whether the request can pass current bucket limits without consuming tokens.
     *
     * @param modelKey bucket key (typically AI model key)
     * @param tpmTokens configured model TPM (fallback capacity before explicit reconfigure)
     * @param requestedTokens estimated token cost for this request
     * @return 0 if allowed; otherwise retry-after seconds
     */
    public long peek(String modelKey, int tpmTokens, long requestedTokens) {
        return evaluateRequest(modelKey, tpmTokens, requestedTokens, false).retryAfterSeconds();
    }

    /**
     * Consumes tokens from the global model bucket when available.
     *
     * @param modelKey bucket key (typically AI model key)
     * @param tpmTokens configured model TPM (fallback capacity before explicit reconfigure)
     * @param requestedTokens estimated token cost for this request
     * @return 0 if allowed and consumed; otherwise retry-after seconds
     */
    public long consume(String modelKey, int tpmTokens, long requestedTokens) {
        return consumeWithResult(modelKey, tpmTokens, requestedTokens).retryAfterSeconds();
    }

    /**
     * Consumes tokens and returns consumed token count for downstream reconciliation.
     *
     * @param modelKey bucket key (typically AI model key)
     * @param tpmTokens configured model TPM (fallback capacity before explicit reconfigure)
     * @param requestedTokens estimated token cost for this request
     * @return consume result with retry-after and consumed tokens
     */
    public ConsumeResult consumeWithResult(String modelKey, int tpmTokens, long requestedTokens) {
        return evaluateRequest(modelKey, tpmTokens, requestedTokens, true);
    }

    /**
     * Applies a post-request usage delta to the bucket.
     * Positive delta means additional debit; negative delta means refund.
     *
     * @param modelKey bucket key
     * @param tpmTokens configured model TPM (fallback capacity before explicit reconfigure)
     * @param deltaTokens signed token delta
     * @return applied delta after clamping/refund bounds
     */
    public long adjustConsumption(String modelKey, int tpmTokens, long deltaTokens) {
        if (modelKey == null || modelKey.isBlank()) {
            return 0L;
        }
        if (tpmTokens <= 0 || deltaTokens == 0L) {
            return 0L;
        }

        long nowMs = clock.millis();
        synchronized (lock) {
            int requestedCapacity = normalizeCapacity(tpmTokens);
            int effectiveCapacity = configuredCapacities.getOrDefault(modelKey, requestedCapacity);
            BucketState state = buckets.computeIfAbsent(
                    modelKey,
                    key -> BucketState.create(effectiveCapacity, nowMs));
            refill(state, nowMs);
            state.reconfigure(effectiveCapacity);

            if (deltaTokens > 0L) {
                state.availableTokens -= deltaTokens;
                return deltaTokens;
            }

            long requestedRefund = Math.abs(deltaTokens);
            double refundable = Math.max(0D, state.capacityTokens - state.availableTokens);
            long appliedRefund = Math.min(requestedRefund, (long) Math.floor(refundable));
            if (appliedRefund <= 0L) {
                return 0L;
            }
            state.availableTokens += appliedRefund;
            return -appliedRefund;
        }
    }

    private ConsumeResult evaluateRequest(String modelKey, int tpmTokens, long requestedTokens, boolean consumeOnAllow) {
        if (modelKey == null || modelKey.isBlank()) {
            return new ConsumeResult(0L, 0L);
        }
        if (tpmTokens <= 0 || requestedTokens <= 0) {
            return new ConsumeResult(0L, 0L);
        }

        long nowMs = clock.millis();
        synchronized (lock) {
            int requestedCapacity = normalizeCapacity(tpmTokens);
            int effectiveCapacity = configuredCapacities.getOrDefault(modelKey, requestedCapacity);
            BucketState state = buckets.computeIfAbsent(
                    modelKey,
                    key -> BucketState.create(effectiveCapacity, nowMs));
            refill(state, nowMs);
            state.reconfigure(effectiveCapacity);

            long cappedRequested = Math.max(1L, Math.min(requestedTokens, (long) state.capacityTokens));
            if (state.availableTokens >= cappedRequested) {
                if (consumeOnAllow) {
                    state.availableTokens -= cappedRequested;
                }
                return new ConsumeResult(0L, consumeOnAllow ? cappedRequested : 0L);
            }

            double deficit = cappedRequested - state.availableTokens;
            long retryAfterMs = (long) Math.ceil(deficit / state.refillTokensPerMs);
            long retryAfterSeconds = Math.max(1L, (retryAfterMs + 999L) / 1000L);
            return new ConsumeResult(retryAfterSeconds, 0L);
        }
    }

    private int normalizeCapacity(long capacityTokens) {
        long safeCapacity = Math.max(1L, capacityTokens);
        return (int) Math.min(Integer.MAX_VALUE, safeCapacity);
    }

    private void refill(BucketState state, long nowMs) {
        if (nowMs <= state.lastRefillMs) {
            return;
        }
        long elapsedMs = nowMs - state.lastRefillMs;
        state.availableTokens = Math.min(
                state.capacityTokens,
                state.availableTokens + (elapsedMs * state.refillTokensPerMs));
        state.lastRefillMs = nowMs;
    }

    private static final class BucketState {
        private int capacityTokens;
        private double refillTokensPerMs;
        private double availableTokens;
        private long lastRefillMs;

        static BucketState create(int capacityTokens, long nowMs) {
            BucketState state = new BucketState();
            state.capacityTokens = capacityTokens;
            state.refillTokensPerMs = capacityTokens / (double) ONE_MINUTE_MS;
            state.availableTokens = capacityTokens;
            state.lastRefillMs = nowMs;
            return state;
        }

        void reconfigure(int newCapacityTokens) {
            if (this.capacityTokens == newCapacityTokens) {
                return;
            }
            this.capacityTokens = newCapacityTokens;
            this.refillTokensPerMs = newCapacityTokens / (double) ONE_MINUTE_MS;
            this.availableTokens = Math.min(this.availableTokens, newCapacityTokens);
        }
    }

    public record ConsumeResult(long retryAfterSeconds, long consumedTokens) {
    }
}
