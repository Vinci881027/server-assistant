package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.config.AiRetryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStreamRetryCoordinatorTest {

    @Test
    void withResilience_httpErrorShouldBeWrappedWithTypedStatusException() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(0);
        properties.setMaxEmptyRetries(0);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        RuntimeException ex = assertInstanceOf(
                RuntimeException.class,
                org.junit.jupiter.api.Assertions.assertThrows(
                        RuntimeException.class,
                        () -> coordinator.withResilience(
                                        ignored -> Flux.error(new HttpServerErrorException(
                                                HttpStatus.SERVICE_UNAVAILABLE,
                                                "Service Unavailable")),
                                        attempt -> Flux.error(new EmptyModelResponseException()))
                                .collectList()
                                .block()));

        ModelHttpStatusException wrapped = assertInstanceOf(ModelHttpStatusException.class, ex);
        assertEquals(503, wrapped.getStatusCode());
        assertEquals(503, coordinator.resolveHttpStatusCode(wrapped));
        assertTrue(wrapped.getCause() instanceof HttpServerErrorException);
    }

    @Test
    void withResilience_retryableHttpErrorShouldRetryAndSucceed() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(1);
        properties.setMaxEmptyRetries(0);
        properties.setHttpInitialBackoffSeconds(0);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        AtomicInteger attempts = new AtomicInteger(0);
        List<String> chunks = coordinator.withResilience(
                        ignored -> Flux.defer(() -> {
                            if (attempts.incrementAndGet() == 1) {
                                return Flux.error(new HttpServerErrorException(
                                        HttpStatus.SERVICE_UNAVAILABLE,
                                        "Service Unavailable"));
                            }
                            return Flux.just("ok");
                        }),
                        attempt -> Flux.error(new EmptyModelResponseException()))
                .collectList()
                .block();

        assertEquals(List.of("ok"), chunks);
        assertEquals(2, attempts.get());
    }

    @Test
    void withResilience_http429ShouldTriggerImmediateRetryAndInvokeCallback() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(1);
        properties.setMaxEmptyRetries(0);
        properties.setHttpInitialBackoffSeconds(5);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger callbackCalls = new AtomicInteger(0);
        Consumer<Throwable> on429Retry = ignored -> callbackCalls.incrementAndGet();

        List<String> chunks = coordinator.withResilience(
                        ignored -> Flux.defer(() -> {
                            if (attempts.incrementAndGet() == 1) {
                                return Flux.error(new ModelHttpStatusException(429, new RuntimeException("rate limit")));
                            }
                            return Flux.just("ok");
                        }),
                        attempt -> Flux.error(new EmptyModelResponseException()),
                        on429Retry)
                .collectList()
                .block();

        assertEquals(List.of("ok"), chunks);
        assertEquals(2, attempts.get());
        assertEquals(1, callbackCalls.get());
    }

    @Test
    void withResilience_http429RetryBudgetShouldBeIndependentFromTransientHttpRetries() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(0);
        properties.setMaxEmptyRetries(0);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        AtomicInteger attempts = new AtomicInteger(0);
        List<String> chunks = coordinator.withResilience(
                        ignored -> Flux.defer(() -> {
                            int currentAttempt = attempts.incrementAndGet();
                            if (currentAttempt <= 2) {
                                return Flux.error(new ModelHttpStatusException(429, new RuntimeException("rate limit")));
                            }
                            return Flux.just("ok");
                        }),
                        attempt -> Flux.error(new EmptyModelResponseException()),
                        ignored -> {},
                        2)
                .collectList()
                .block();

        assertEquals(List.of("ok"), chunks);
        assertEquals(3, attempts.get());
    }

    @Test
    void withResilience_http401ShouldTriggerImmediateRetryAndInvokeCallback() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(0);
        properties.setMaxEmptyRetries(0);
        properties.setHttpInitialBackoffSeconds(5);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        AtomicInteger attempts = new AtomicInteger(0);
        AtomicInteger callbackCalls = new AtomicInteger(0);
        List<String> chunks = coordinator.withResilience(
                        ignored -> Flux.defer(() -> {
                            if (attempts.incrementAndGet() == 1) {
                                return Flux.error(new ModelHttpStatusException(401, new RuntimeException("unauthorized")));
                            }
                            return Flux.just("ok");
                        }),
                        attempt -> Flux.error(new EmptyModelResponseException()),
                        ignored -> callbackCalls.incrementAndGet(),
                        1)
                .collectList()
                .block();

        assertEquals(List.of("ok"), chunks);
        assertEquals(2, attempts.get());
        assertEquals(1, callbackCalls.get());
    }

    @Test
    void withResilience_http403ShouldStopAfterRetryBudgetExhausted() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(0);
        properties.setMaxEmptyRetries(0);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        AtomicInteger attempts = new AtomicInteger(0);
        ModelHttpStatusException ex = assertThrows(
                ModelHttpStatusException.class,
                () -> coordinator.withResilience(
                                ignored -> Flux.defer(() -> {
                                    attempts.incrementAndGet();
                                    return Flux.error(new ModelHttpStatusException(403, new RuntimeException("forbidden")));
                                }),
                                attempt -> Flux.error(new EmptyModelResponseException()),
                                ignored -> {},
                                1)
                        .collectList()
                        .block());

        assertEquals(403, ex.getStatusCode());
        assertEquals(2, attempts.get());
    }

    @Test
    void withResilience_nonRetryableHttp429ShouldNotBackoffOrRetry() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(0);
        properties.setMaxEmptyRetries(0);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        AtomicInteger attempts = new AtomicInteger(0);
        ModelHttpStatusException ex = assertThrows(
                ModelHttpStatusException.class,
                () -> coordinator.withResilience(
                                ignored -> Flux.defer(() -> {
                                    attempts.incrementAndGet();
                                    return Flux.error(new ModelHttpStatusException(
                                            429,
                                            new IllegalStateException("All Groq API keys are temporarily rate-limited"),
                                            null,
                                            false));
                                }),
                                attempt -> Flux.error(new EmptyModelResponseException()),
                                ignored -> {},
                                3)
                        .collectList()
                        .block());

        assertEquals(429, ex.getStatusCode());
        assertEquals(1, attempts.get());
    }

    @Test
    void resolveRateLimitResetDelayMillis_shouldReadGroqResetHeader() {
        AiRetryProperties properties = new AiRetryProperties();
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        HttpHeaders headers = new HttpHeaders();
        headers.set("x-ratelimit-reset-tokens", "12.5s");
        WebClientResponseException ex = WebClientResponseException.create(
                429,
                "Too Many Requests",
                headers,
                new byte[0],
                StandardCharsets.UTF_8);

        Long delayMs = coordinator.resolveRateLimitResetDelayMillis(ex);
        assertEquals(12_500L, delayMs);
    }

    @Test
    void resolveRateLimitResetDelayMillis_shouldFallbackToRetryAfterHeader() {
        AiRetryProperties properties = new AiRetryProperties();
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "3");
        WebClientResponseException ex = WebClientResponseException.create(
                429,
                "Too Many Requests",
                headers,
                new byte[0],
                StandardCharsets.UTF_8);

        Long delayMs = coordinator.resolveRateLimitResetDelayMillis(ex);
        assertEquals(3_000L, delayMs);
    }

    @Test
    void resolveHttpStatusCode_plainMessageShouldNotBeParsed() {
        AiRetryProperties properties = new AiRetryProperties();
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        RuntimeException ex = new RuntimeException("HTTP status: 503 Service Unavailable");
        assertNull(coordinator.resolveHttpStatusCode(ex));
    }

    @Test
    void withResilience_temperatureShouldBeClampedToGroqRange() {
        AiRetryProperties properties = new AiRetryProperties();
        properties.setMaxHttpRetries(0);
        properties.setMaxEmptyRetries(1);
        properties.setTemperatureBase(-0.4);
        properties.setTemperatureOnEmptyRetry(2.8);
        AiStreamRetryCoordinator coordinator = new AiStreamRetryCoordinator(properties);

        List<Double> temperatures = new ArrayList<>();
        List<String> chunks = coordinator.withResilience(
                        temperature -> Flux.defer(() -> {
                            temperatures.add(temperature);
                            if (temperatures.size() == 1) {
                                return Flux.empty();
                            }
                            return Flux.just("ok");
                        }),
                        attempt -> Flux.error(new EmptyModelResponseException()))
                .collectList()
                .block();

        assertEquals(List.of(0.0, 2.0), temperatures);
        assertEquals(List.of("ok"), chunks);
    }

}
