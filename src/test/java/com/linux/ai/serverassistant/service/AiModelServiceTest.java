package com.linux.ai.serverassistant.service;

import com.linux.ai.serverassistant.config.AiModelProperties;
import com.linux.ai.serverassistant.entity.AiModelConfig;
import com.linux.ai.serverassistant.repository.AiModelConfigRepository;
import com.linux.ai.serverassistant.service.security.TpmBucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiModelServiceTest {

    private AiModelConfigRepository repository;
    private TpmBucket tpmBucket;
    private AiModelService service;

    @BeforeEach
    void setUp() {
        repository = mock(AiModelConfigRepository.class);
        AiModelProperties properties = new AiModelProperties();
        tpmBucket = mock(TpmBucket.class);

        when(repository.findAll()).thenReturn(List.of(
                new AiModelConfig("120b", "openai/gpt-oss-120b", 8000, "GPT OSS 120B", "High Intelligence", true),
                new AiModelConfig("20b", "openai/gpt-oss-20b", 8000, "GPT OSS 20B", "High Intelligence", true),
                new AiModelConfig("scout", "meta-llama/llama-4-scout-17b-16e-instruct", 30000, "Llama 4 17B (Scout)", "Fast & Balanced", true)
        ));

        service = new AiModelService(repository, properties, tpmBucket);
    }

    @Test
    void getClientModelsAsMap_shouldExposeAvailableFlagAndSuggestedAlternative() {
        service.recordModelRateLimit("120b");

        Map<String, AiModelProperties.ModelConfig> models = service.getClientModelsAsMap();

        assertFalse(models.get("120b").isAvailable());
        assertEquals("scout", models.get("120b").getSuggestAlternative());
        assertTrue(models.get("20b").isAvailable());
        assertNull(models.get("20b").getSuggestAlternative());
        assertTrue(models.get("scout").isAvailable());
    }

    @Test
    void getClientModelsAsMap_keyModeWithHealthySpareKey_shouldKeepModelAvailable() {
        service.configureGroqApiKeyCount(2);
        service.recordKeyRateLimit(0);
        service.recordModelRateLimit("120b");

        Map<String, AiModelProperties.ModelConfig> models = service.getClientModelsAsMap();

        assertTrue(models.get("120b").isAvailable());
        assertNull(models.get("120b").getSuggestAlternative());
    }

    @Test
    void countHealthyGroqApiKeys_shouldReflectRateLimitedKeys() {
        service.configureGroqApiKeyCount(3);
        service.recordKeyRateLimit(0);
        service.recordKeyRateLimit(2);

        assertEquals(1, service.countHealthyGroqApiKeys());
    }

    @Test
    void countHealthyGroqApiKeys_shouldExcludeAuthenticationFailedKeys() {
        service.configureGroqApiKeyCount(3);
        service.recordKeyAuthenticationFailure(1);

        assertTrue(service.isKeyAuthenticationFailed(1));
        assertEquals(2, service.countHealthyGroqApiKeys());
    }

    @Test
    void getClientModelsAsMap_keyModeWhenAllKeysRateLimited_shouldMarkAllModelsUnavailable() {
        service.configureGroqApiKeyCount(2);
        service.recordKeyRateLimit(0);
        service.recordKeyRateLimit(1);

        Map<String, AiModelProperties.ModelConfig> models = service.getClientModelsAsMap();

        assertFalse(models.get("120b").isAvailable());
        assertFalse(models.get("20b").isAvailable());
        assertNull(models.get("120b").getSuggestAlternative());
    }

    @Test
    void recordKeyRateLimit_withCustomCooldown_shouldExpireByDeadline() throws InterruptedException {
        service.recordKeyRateLimit(0, 20L);
        assertTrue(service.isKeyRateLimited(0));

        Thread.sleep(50L);
        assertFalse(service.isKeyRateLimited(0));
    }

    @Test
    void recordKeyRateLimit_withoutExplicitCooldown_shouldUseConfiguredDefault() throws Exception {
        Field defaultWindowField = AiModelService.class.getDeclaredField("defaultKeyRateLimitWindowMs");
        defaultWindowField.setAccessible(true);
        defaultWindowField.setLong(service, 20L);

        service.recordKeyRateLimit(0);
        assertTrue(service.isKeyRateLimited(0));

        Thread.sleep(50L);
        assertFalse(service.isKeyRateLimited(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordKeyRateLimit_withoutExplicitCooldown_shouldApplyExponentialBackoff() throws Exception {
        Field defaultWindowField = AiModelService.class.getDeclaredField("defaultKeyRateLimitWindowMs");
        defaultWindowField.setAccessible(true);
        defaultWindowField.setLong(service, 1_000L);
        Field keyCooldownMapField = AiModelService.class.getDeclaredField("keyRateLimitedUntilEpochMs");
        keyCooldownMapField.setAccessible(true);
        Map<Integer, Long> keyCooldownMap = (Map<Integer, Long>) keyCooldownMapField.get(service);

        service.recordKeyRateLimit(0);
        Long firstCooldownUntil = keyCooldownMap.get(0);
        assertNotNull(firstCooldownUntil);
        long firstRemainingMs = firstCooldownUntil - System.currentTimeMillis();
        assertTrue(firstRemainingMs >= 800L);

        service.recordKeyRateLimit(0);
        Long secondCooldownUntil = keyCooldownMap.get(0);
        assertNotNull(secondCooldownUntil);
        long secondRemainingMs = secondCooldownUntil - System.currentTimeMillis();
        assertTrue(secondRemainingMs >= 1_800L);
        assertTrue(secondRemainingMs > firstRemainingMs);
    }

    @Test
    void recordKeyRateLimit_withZeroCooldown_shouldClearCurrentCooldown() {
        service.recordKeyRateLimit(0, 5_000L);
        assertTrue(service.isKeyRateLimited(0));

        service.recordKeyRateLimit(0, 0L);
        assertFalse(service.isKeyRateLimited(0));
    }

    @Test
    void configureGroqApiKeyCount_shouldReconfigureBucketsWithScaledCapacity() {
        service.getModelsAsMap();
        clearInvocations(tpmBucket);

        service.configureGroqApiKeyCount(3);

        verify(tpmBucket).reconfigure("120b", 24_000L);
        verify(tpmBucket).reconfigure("20b", 24_000L);
        verify(tpmBucket).reconfigure("scout", 90_000L);
    }

    @Test
    void recordKeyRateLimit_shouldReduceBucketCapacityByHealthyKeyCount() {
        service.configureGroqApiKeyCount(3);
        clearInvocations(tpmBucket);

        service.recordKeyRateLimit(1, 5_000L);

        verify(tpmBucket).reconfigure("120b", 16_000L);
        verify(tpmBucket).reconfigure("20b", 16_000L);
        verify(tpmBucket).reconfigure("scout", 60_000L);
    }

    @Test
    void recordKeyAuthenticationFailure_shouldReduceBucketCapacityByHealthyKeyCount() {
        service.configureGroqApiKeyCount(3);
        clearInvocations(tpmBucket);

        service.recordKeyAuthenticationFailure(2);

        verify(tpmBucket).reconfigure("120b", 16_000L);
        verify(tpmBucket).reconfigure("20b", 16_000L);
        verify(tpmBucket).reconfigure("scout", 60_000L);
    }

    @Test
    void configureGroqApiKeyCount_shouldEvictOutOfRangeAuthenticationFailures() {
        service.configureGroqApiKeyCount(3);
        service.recordKeyAuthenticationFailure(2);
        assertTrue(service.isKeyAuthenticationFailed(2));

        service.configureGroqApiKeyCount(2);

        assertFalse(service.isKeyAuthenticationFailed(2));
    }

    @Test
    void evictExpiredKeyCooldownsAndReconfigureBuckets_shouldRestoreCapacityAfterCooldown() throws InterruptedException {
        service.configureGroqApiKeyCount(2);
        service.recordKeyRateLimit(0, 20L);
        clearInvocations(tpmBucket);

        Thread.sleep(50L);
        service.evictExpiredKeyCooldownsAndReconfigureBuckets();

        verify(tpmBucket, atLeastOnce()).reconfigure("120b", 16_000L);
        verify(tpmBucket, atLeastOnce()).reconfigure("20b", 16_000L);
        verify(tpmBucket, atLeastOnce()).reconfigure("scout", 60_000L);
        assertFalse(service.isKeyRateLimited(0));
    }

    @Test
    void deleteAll_shouldDeleteModelsAndClearTpmBuckets() {
        service.deleteAll();

        verify(repository).deleteAll();
        verify(tpmBucket).clear();
    }
}
