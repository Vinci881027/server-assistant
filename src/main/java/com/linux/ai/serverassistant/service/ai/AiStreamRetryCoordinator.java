package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.config.AiRetryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Coordinates the two-layer retry strategy for AI model streaming:
 *
 * <ol>
 *   <li><b>HTTP retry (inner)</b> — retries 429/401/403/500/503 responses; 429/401/403 perform API-key
 *       failover (429 uses a short progressive delay, 401/403 retry immediately), while 500/503 use
 *       exponential backoff.</li>
 *   <li><b>Empty-response retry (outer)</b> — retries when the model returns zero content,
 *       adjusting temperature to encourage variation on subsequent attempts.</li>
 * </ol>
 *
 * <p>All retry parameters are driven by {@link AiRetryProperties} and can be tuned via
 * {@code application.properties} without recompilation.
 */
@Component
public class AiStreamRetryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AiStreamRetryCoordinator.class);
    private static final long MAX_429_RESET_DELAY_MS = Duration.ofMinutes(5).toMillis();
    private static final long HTTP_429_KEY_ROTATION_DELAY_STEP_MS = 200L;
    private static final long HTTP_429_KEY_ROTATION_DELAY_MAX_MS = 1_000L;
    private static final Pattern DURATION_TOKEN_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|s|m|h)", Pattern.CASE_INSENSITIVE);

    /** Called when the model returns an empty response; may return content or signal failure. */
    @FunctionalInterface
    public interface EmptyResponseHandler {
        /**
         * @param emptyRetryAttempt zero-based attempt index (0 = first empty, 1 = second empty, …)
         * @return Flux that either emits fallback content or errors with {@link EmptyModelResponseException}
         */
        Flux<String> handle(int emptyRetryAttempt);
    }

    private final AiRetryProperties retryProperties;

    public AiStreamRetryCoordinator(AiRetryProperties retryProperties) {
        this.retryProperties = retryProperties;
    }

    /**
     * Wraps a model stream factory with both retry layers.
     *
     * @param modelStreamFactory  accepts the temperature to use and returns a raw model stream
     * @param emptyResponseHandler called when the model emits nothing; use it to clean up and
     *                             either return fallback content or throw {@link EmptyModelResponseException}
     * @return resilient Flux that applies HTTP + empty-response retry according to configuration
     */
    public Flux<String> withResilience(
            Function<Double, Flux<String>> modelStreamFactory,
            EmptyResponseHandler emptyResponseHandler) {
        return withResilience(modelStreamFactory, emptyResponseHandler, ignored -> {});
    }

    /**
     * Wraps a model stream factory with both retry layers and a callback for key-failover retries.
     *
     * @param modelStreamFactory accepts the temperature to use and returns a raw model stream
     * @param emptyResponseHandler called when the model emits nothing; use it to clean up and
     *                             either return fallback content or throw {@link EmptyModelResponseException}
     * @param onHttp429Retry callback invoked before each key-failover retry (429/401/403, no-op if null)
     * @return resilient Flux that applies HTTP + empty-response retry according to configuration
     */
    public Flux<String> withResilience(
            Function<Double, Flux<String>> modelStreamFactory,
            EmptyResponseHandler emptyResponseHandler,
            Consumer<Throwable> onHttp429Retry) {
        return withResilience(
                modelStreamFactory,
                emptyResponseHandler,
                onHttp429Retry,
                retryProperties.getMaxHttpRetries());
    }

    /**
     * Wraps a model stream factory with both retry layers and customizable key-failover retry budget.
     *
     * @param modelStreamFactory accepts the temperature to use and returns a raw model stream
     * @param emptyResponseHandler called when the model emits nothing; use it to clean up and
     *                             either return fallback content or throw {@link EmptyModelResponseException}
     * @param onHttp429Retry callback invoked before each key-failover retry (429/401/403, no-op if null)
     * @param maxHttp429Retries maximum key-failover retry attempts; values below 0 are treated as 0
     * @return resilient Flux that applies HTTP + empty-response retry according to configuration
     */
    public Flux<String> withResilience(
            Function<Double, Flux<String>> modelStreamFactory,
            EmptyResponseHandler emptyResponseHandler,
            Consumer<Throwable> onHttp429Retry,
            int maxHttp429Retries) {
        Consumer<Throwable> safeOnKeyFailoverRetry = onHttp429Retry == null ? ignored -> {} : onHttp429Retry;
        int boundedMaxKeyFailoverRetries = Math.max(0, maxHttp429Retries);

        return Flux.defer(() -> {
            AtomicInteger emptyRetryCount = new AtomicInteger(0);
            AtomicInteger transientHttpRetryCount = new AtomicInteger(0);
            AtomicInteger keyFailoverRetryCount = new AtomicInteger(0);

            Flux<String> inner = buildInnerStream(
                    modelStreamFactory,
                    emptyResponseHandler,
                    emptyRetryCount,
                    transientHttpRetryCount,
                    keyFailoverRetryCount,
                    safeOnKeyFailoverRetry,
                    boundedMaxKeyFailoverRetries);

            return wrapWithEmptyRetry(inner, emptyRetryCount);
        });
    }

    // ========== HTTP status extraction (package-visible for handleStreamError in ChatService) ==========

    /** Resolves an HTTP status code from a throwable chain using typed exceptions only. */
    public Integer resolveHttpStatusCode(Throwable throwable) {
        return extractHttpStatusCode(throwable);
    }

    /**
     * Resolves a 429 reset hint (milliseconds) from throwable chain.
     * Prioritizes Groq's x-ratelimit-reset-tokens header, then Retry-After.
     */
    public Long resolveRateLimitResetDelayMillis(Throwable throwable) {
        return extractRateLimitResetDelayMillis(throwable);
    }

    // ========== Private implementation ==========

    private Flux<String> buildInnerStream(
            Function<Double, Flux<String>> modelStreamFactory,
            EmptyResponseHandler emptyResponseHandler,
            AtomicInteger emptyRetryCount,
            AtomicInteger transientHttpRetryCount,
            AtomicInteger keyFailoverRetryCount,
            Consumer<Throwable> onKeyFailoverRetry,
            int maxKeyFailoverRetries) {
        return Flux.defer(() -> {
            double temperature = emptyRetryCount.get() == 0
                    ? retryProperties.getTemperatureBase()
                    : retryProperties.getTemperatureOnEmptyRetry();
            double boundedTemperature = Math.min(2.0, Math.max(0.0, temperature));
            return modelStreamFactory.apply(boundedTemperature)
                    .onErrorMap(this::attachHttpStatusCode)
                    .retryWhen(buildHttpRetrySpec(
                            transientHttpRetryCount,
                            keyFailoverRetryCount,
                            onKeyFailoverRetry,
                            maxKeyFailoverRetries))
                    .switchIfEmpty(Flux.defer(() -> emptyResponseHandler.handle(emptyRetryCount.get())));
        });
    }

    private Flux<String> wrapWithEmptyRetry(Flux<String> inner, AtomicInteger emptyRetryCount) {
        return inner
                .retryWhen(Retry.max(retryProperties.getMaxEmptyRetries())
                        .filter(EmptyModelResponseException.class::isInstance)
                        .doBeforeRetry(signal -> emptyRetryCount.incrementAndGet())
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                .onErrorResume(EmptyModelResponseException.class,
                        ignored -> Flux.just("⚠️ AI 未回傳內容。請嘗試重新輸入指令。"));
    }

    private Retry buildHttpRetrySpec(
            AtomicInteger transientHttpRetryCount,
            AtomicInteger keyFailoverRetryCount,
            Consumer<Throwable> onKeyFailoverRetry,
            int maxKeyFailoverRetries) {
        return Retry.from(retrySignals -> retrySignals.concatMap(signal -> {
            Throwable failure = signal.failure();
            Integer httpStatus = resolveHttpStatusCode(failure);
            if (isKeyFailoverHttpStatus(httpStatus)) {
                if (!isRetryableKeyFailoverFailure(httpStatus, failure)) {
                    return Mono.error(failure);
                }
                int currentFailoverCount = keyFailoverRetryCount.get();
                if (currentFailoverCount >= maxKeyFailoverRetries) {
                    return Mono.error(failure);
                }
                int attempt = keyFailoverRetryCount.incrementAndGet();
                Long resetDelayMs = httpStatus != null && httpStatus == 429
                        ? resolveRateLimitResetDelayMillis(failure)
                        : null;
                onKeyFailoverRetry.accept(failure);
                long keyRotationDelayMs = resolveKeyFailoverDelayMillis(httpStatus, attempt);
                log.warn("[HTTP_RETRY] model stream key-failover retry #{} in {}ms due to httpStatus={}; resetHintMs={} ({})",
                        attempt,
                        keyRotationDelayMs,
                        httpStatus,
                        resetDelayMs,
                        failure == null || failure.getMessage() == null ? "未知" : failure.getMessage());
                return Mono.delay(Duration.ofMillis(keyRotationDelayMs));
            }
            if (!isRetryableTransientModelError(httpStatus)) {
                return Mono.error(failure);
            }

            int currentTransientCount = transientHttpRetryCount.get();
            if (currentTransientCount >= retryProperties.getMaxHttpRetries()) {
                return Mono.error(failure);
            }

            int attempt = transientHttpRetryCount.incrementAndGet();
            Duration backoff = resolveTransientBackoff(attempt);
            log.warn("[HTTP_RETRY] model stream retry #{} in {}s due to httpStatus={} ({})",
                    attempt,
                    backoff.toSeconds(),
                    httpStatus,
                    failure == null || failure.getMessage() == null ? "未知" : failure.getMessage());
            return Mono.delay(backoff);
        }));
    }

    private boolean isKeyFailoverHttpStatus(Integer httpStatus) {
        return httpStatus != null && (httpStatus == 429 || httpStatus == 401 || httpStatus == 403);
    }

    private boolean isRetryableKeyFailoverFailure(Integer httpStatus, Throwable failure) {
        if (httpStatus == null) {
            return false;
        }
        if (httpStatus == 429) {
            return !(failure instanceof ModelHttpStatusException ex) || ex.isRetryable();
        }
        return !(failure instanceof ModelHttpStatusException ex) || ex.isRetryable();
    }

    private boolean isRetryableTransientModelError(Integer httpStatus) {
        return httpStatus != null && (httpStatus == 500 || httpStatus == 503);
    }

    private Duration resolveTransientBackoff(int attempt) {
        return Duration.ofSeconds(retryProperties.getHttpInitialBackoffSeconds())
                .multipliedBy(1L << (attempt - 1));
    }

    private long resolveKeyFailoverDelayMillis(Integer httpStatus, int attempt) {
        if (httpStatus == null || httpStatus != 429) {
            return 0L;
        }
        if (attempt <= 1) {
            return 0L;
        }
        long delayMs = HTTP_429_KEY_ROTATION_DELAY_STEP_MS * (attempt - 1L);
        return Math.min(delayMs, HTTP_429_KEY_ROTATION_DELAY_MAX_MS);
    }

    private Integer extractHttpStatusCode(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelHttpStatusException ex) {
                return ex.getStatusCode();
            }
            if (current instanceof HttpClientErrorException ex) {
                return ex.getStatusCode().value();
            }
            if (current instanceof HttpServerErrorException ex) {
                return ex.getStatusCode().value();
            }
            if (current instanceof WebClientResponseException ex) {
                return ex.getStatusCode().value();
            }
            if (current instanceof ExecutionException || current instanceof CompletionException) {
                current = current.getCause();
                continue;
            }
            current = current.getCause();
        }
        return null;
    }

    private Throwable attachHttpStatusCode(Throwable throwable) {
        if (throwable instanceof ModelHttpStatusException) {
            return throwable;
        }
        Integer statusCode = extractHttpStatusCode(throwable);
        if (statusCode == null) {
            return throwable;
        }
        Long resetDelayMs = statusCode == 429 ? extractRateLimitResetDelayMillis(throwable) : null;
        return new ModelHttpStatusException(statusCode, throwable, resetDelayMs);
    }

    private Long extractRateLimitResetDelayMillis(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ModelHttpStatusException ex && ex.getRetryAfterMillis() != null) {
                return ex.getRetryAfterMillis();
            }
            if (current instanceof HttpClientErrorException ex) {
                Long delayMs = extractRateLimitResetDelayMillis(ex.getResponseHeaders());
                if (delayMs != null) {
                    return delayMs;
                }
            }
            if (current instanceof WebClientResponseException ex) {
                Long delayMs = extractRateLimitResetDelayMillis(ex.getHeaders());
                if (delayMs != null) {
                    return delayMs;
                }
            }
            if (current instanceof HttpServerErrorException ex) {
                Long delayMs = extractRateLimitResetDelayMillis(ex.getResponseHeaders());
                if (delayMs != null) {
                    return delayMs;
                }
            }
            if (current instanceof ExecutionException || current instanceof CompletionException) {
                current = current.getCause();
                continue;
            }
            current = current.getCause();
        }
        return null;
    }

    private Long extractRateLimitResetDelayMillis(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }

        Long delayMs = parseRateLimitResetHeader(headers.getFirst("x-ratelimit-reset-tokens"));
        if (delayMs != null) {
            return delayMs;
        }
        delayMs = parseRateLimitResetHeader(headers.getFirst("x-ratelimit-reset-requests"));
        if (delayMs != null) {
            return delayMs;
        }
        return parseRetryAfterHeader(headers.getFirst(HttpHeaders.RETRY_AFTER));
    }

    private Long parseRateLimitResetHeader(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        Long durationMs = parseDurationTokensToMillis(rawValue);
        if (durationMs != null) {
            return clampResetDelay(durationMs);
        }

        Long numericMs = parseNumericDurationOrEpochMillis(rawValue);
        if (numericMs != null) {
            return clampResetDelay(numericMs);
        }
        return null;
    }

    private Long parseRetryAfterHeader(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        Long numericMs = parseNumericDurationOrEpochMillis(rawValue);
        if (numericMs != null) {
            return clampResetDelay(numericMs);
        }

        try {
            Instant retryAt = ZonedDateTime.parse(rawValue, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            long delayMs = Duration.between(Instant.now(), retryAt).toMillis();
            return clampResetDelay(delayMs);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Long parseDurationTokensToMillis(String rawValue) {
        String compact = rawValue.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        Matcher matcher = DURATION_TOKEN_PATTERN.matcher(compact);
        double totalMillis = 0.0d;
        int tokenEnd = 0;
        boolean found = false;
        while (matcher.find()) {
            if (matcher.start() != tokenEnd) {
                return null;
            }
            found = true;
            double value = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2).toLowerCase(Locale.ROOT);
            if ("h".equals(unit)) {
                totalMillis += value * 3_600_000d;
            } else if ("m".equals(unit)) {
                totalMillis += value * 60_000d;
            } else if ("s".equals(unit)) {
                totalMillis += value * 1_000d;
            } else if ("ms".equals(unit)) {
                totalMillis += value;
            }
            tokenEnd = matcher.end();
        }
        if (!found || tokenEnd != compact.length()) {
            return null;
        }
        return Math.round(totalMillis);
    }

    private Long parseNumericDurationOrEpochMillis(String rawValue) {
        String compact = rawValue.trim();
        if (compact.isEmpty()) {
            return null;
        }
        try {
            double value = Double.parseDouble(compact);
            if (value < 0.0d) {
                return null;
            }

            long rounded = Math.round(value);
            if (rounded >= 1_000_000_000_000L) {
                return rounded - System.currentTimeMillis();
            }
            if (rounded >= 1_000_000_000L) {
                return (rounded * 1_000L) - System.currentTimeMillis();
            }
            return Math.round(value * 1_000d);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long clampResetDelay(long delayMs) {
        if (delayMs <= 0) {
            return 0L;
        }
        return Math.min(delayMs, MAX_429_RESET_DELAY_MS);
    }

}
