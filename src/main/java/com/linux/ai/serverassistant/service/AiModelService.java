package com.linux.ai.serverassistant.service;

import com.linux.ai.serverassistant.config.AiModelProperties;
import com.linux.ai.serverassistant.entity.AiModelConfig;
import com.linux.ai.serverassistant.repository.AiModelConfigRepository;
import com.linux.ai.serverassistant.service.security.TpmBucket;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Service
public class AiModelService {
    private static final long CACHE_TTL_MS = 60_000L;
    private static final long MODEL_RATE_LIMIT_WINDOW_MS = 60_000L;
    private static final long DEFAULT_KEY_RATE_LIMIT_WINDOW_MS = 90_000L;
    private static final long MAX_KEY_RATE_LIMIT_BACKOFF_MS = 5 * 60_000L;

    /** Immutable snapshot of the model map plus its expiry timestamp. */
    private record CachedModels(Map<String, AiModelProperties.ModelConfig> models, long expiryMs) {}

    private volatile CachedModels modelCache = null;
    private final Map<String, ConcurrentLinkedDeque<Long>> recent429ByModel = new ConcurrentHashMap<>();
    private final Map<Integer, Long> keyRateLimitedUntilEpochMs = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> keyFallbackBackoffAttempts = new ConcurrentHashMap<>();
    private final Set<Integer> keyAuthenticationFailedIndices = ConcurrentHashMap.newKeySet();
    private volatile int groqApiKeyCount = 0;
    @Value("${app.ai.key-cooldown-ms:" + DEFAULT_KEY_RATE_LIMIT_WINDOW_MS + "}")
    private long defaultKeyRateLimitWindowMs = DEFAULT_KEY_RATE_LIMIT_WINDOW_MS;

    private final AiModelConfigRepository repository;
    private final AiModelProperties properties;
    private final TpmBucket tpmBucket;

    public AiModelService(
            AiModelConfigRepository repository,
            AiModelProperties properties,
            TpmBucket tpmBucket) {
        this.repository = repository;
        this.properties = properties;
        this.tpmBucket = tpmBucket;
    }

    @PostConstruct
    public void init() {
        // Seed defaults from application.properties.
        // Do not overwrite existing DB rows so Admin changes persist across restarts.
        if (properties.getModels() != null) {
            properties.getModels().forEach((key, config) -> {
                if (repository.existsById(key)) return;
                AiModelConfig entity = new AiModelConfig(
                    key,
                    config.getName(),
                    config.getTpm(),
                    config.getLabel(),
                    config.getCategory(),
                    config.isEnabled()
                );
                repository.save(entity);
            });
        }
        reconfigureAllBuckets();
    }

    public List<AiModelConfig> getAllModels() {
        return repository.findAll();
    }

    // Convert to the Map format originally expected by ChatController to maintain compatibility.
    public Map<String, AiModelProperties.ModelConfig> getModelsAsMap() {
        CachedModels cached = modelCache;
        if (cached != null && System.currentTimeMillis() < cached.expiryMs()) {
            return cached.models();
        }
        Map<String, AiModelProperties.ModelConfig> fresh = loadModelsFromDb();
        modelCache = new CachedModels(fresh, System.currentTimeMillis() + CACHE_TTL_MS);
        reconfigureBuckets(fresh);
        return fresh;
    }

    public Map<String, AiModelProperties.ModelConfig> getClientModelsAsMap() {
        Map<String, AiModelProperties.ModelConfig> models = getModelsAsMap();
        long now = System.currentTimeMillis();

        return models.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> toClientModelConfig(entry.getKey(), entry.getValue(), models, now),
                (left, right) -> left,
                LinkedHashMap::new
        ));
    }

    public void recordModelRateLimit(String modelKey) {
        if (modelKey == null || modelKey.isBlank()) {
            return;
        }
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> hits = recent429ByModel.computeIfAbsent(modelKey, ignored -> new ConcurrentLinkedDeque<>());
        hits.addLast(now);
        evictExpiredRateLimitHits(hits, now);
    }

    public void recordKeyRateLimit(int keyIndex) {
        recordKeyRateLimit(keyIndex, null);
    }

    public void recordKeyRateLimit(int keyIndex, Long cooldownMs) {
        if (keyIndex < 0) {
            return;
        }
        long normalizedCooldownMs = resolveKeyCooldownMs(keyIndex, cooldownMs);
        if (normalizedCooldownMs <= 0) {
            clearKeyRateLimitState(keyIndex);
            reconfigureAllBuckets();
            return;
        }
        long cooldownUntil = System.currentTimeMillis() + normalizedCooldownMs;
        keyRateLimitedUntilEpochMs.merge(keyIndex, cooldownUntil, Math::max);
        reconfigureAllBuckets();
    }

    public boolean isKeyRateLimited(int keyIndex) {
        if (keyIndex < 0) {
            return false;
        }
        Long cooldownUntil = keyRateLimitedUntilEpochMs.get(keyIndex);
        if (cooldownUntil == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (cooldownUntil <= now) {
            if (clearKeyRateLimitState(keyIndex, cooldownUntil)) {
                reconfigureAllBuckets();
            }
            return false;
        }
        return true;
    }

    public void recordKeyAuthenticationFailure(int keyIndex) {
        if (keyIndex < 0) {
            return;
        }
        boolean newlyFailed = keyAuthenticationFailedIndices.add(keyIndex);
        boolean clearedCooldown = keyRateLimitedUntilEpochMs.remove(keyIndex) != null;
        boolean clearedFallback = keyFallbackBackoffAttempts.remove(keyIndex) != null;
        if (newlyFailed || clearedCooldown || clearedFallback) {
            reconfigureAllBuckets();
        }
    }

    public boolean isKeyAuthenticationFailed(int keyIndex) {
        if (keyIndex < 0) {
            return false;
        }
        return keyAuthenticationFailedIndices.contains(keyIndex);
    }

    public void configureGroqApiKeyCount(int keyCount) {
        groqApiKeyCount = Math.max(0, keyCount);
        evictOutOfRangeKeyState(groqApiKeyCount);
        reconfigureAllBuckets();
    }

    public int getGroqApiKeyCount() {
        return groqApiKeyCount;
    }

    public long getKeyCooldownMs() {
        return Math.max(0L, defaultKeyRateLimitWindowMs);
    }

    public int countHealthyGroqApiKeys() {
        if (groqApiKeyCount <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        int healthyCount = 0;
        for (int i = 0; i < groqApiKeyCount; i++) {
            if (!isKeyRateLimitedAt(i, now) && !isKeyAuthenticationFailedAt(i)) {
                healthyCount++;
            }
        }
        return healthyCount;
    }

    @Scheduled(fixedRateString = "${app.ai.key-cooldown-cleanup-interval-ms:1000}")
    void evictExpiredKeyCooldownsAndReconfigureBuckets() {
        if (evictExpiredKeyCooldowns(System.currentTimeMillis())) {
            reconfigureAllBuckets();
        }
    }

    private Map<String, AiModelProperties.ModelConfig> loadModelsFromDb() {
        return repository.findAll().stream()
            .filter(AiModelConfig::isEnabled) // Only return enabled models.
            .collect(Collectors.toMap(
                AiModelConfig::getId,
                model -> {
                    AiModelProperties.ModelConfig config = new AiModelProperties.ModelConfig();
                    config.setName(model.getName());
                    config.setTpm(model.getTpm());
                    config.setLabel(model.getLabel());
                    config.setCategory(model.getCategory());
                    config.setEnabled(model.isEnabled());
                    return config;
                }
            ));
    }

    private AiModelProperties.ModelConfig toClientModelConfig(
            String modelKey,
            AiModelProperties.ModelConfig source,
            Map<String, AiModelProperties.ModelConfig> allModels,
            long now) {
        AiModelProperties.ModelConfig view = new AiModelProperties.ModelConfig();
        view.setName(source.getName());
        view.setTpm(source.getTpm());
        view.setLabel(source.getLabel());
        view.setCategory(source.getCategory());
        view.setEnabled(source.isEnabled());

        boolean available = isModelAvailable(modelKey, now);
        view.setAvailable(available);
        view.setSuggestAlternative(available ? null : chooseSuggestedAlternative(modelKey, allModels, now));
        return view;
    }

    private String chooseSuggestedAlternative(
            String modelKey,
            Map<String, AiModelProperties.ModelConfig> allModels,
            long now) {
        return allModels.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(modelKey))
                .filter(entry -> isModelAvailable(entry.getKey(), now))
                .max(Comparator
                        .comparingInt((Map.Entry<String, AiModelProperties.ModelConfig> entry) -> entry.getValue().getTpm())
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private boolean isModelAvailable(String modelKey, long now) {
        if (groqApiKeyCount > 0) {
            return countHealthyGroqApiKeys() > 0;
        }
        return getRecentRateLimitCount(modelKey, now) == 0;
    }

    private int getRecentRateLimitCount(String modelKey, long now) {
        ConcurrentLinkedDeque<Long> hits = recent429ByModel.get(modelKey);
        if (hits == null) {
            return 0;
        }
        evictExpiredRateLimitHits(hits, now);
        return hits.size();
    }

    private void evictExpiredRateLimitHits(ConcurrentLinkedDeque<Long> hits, long now) {
        long cutoff = now - MODEL_RATE_LIMIT_WINDOW_MS;
        while (true) {
            Long head = hits.peekFirst();
            if (head == null || head >= cutoff) {
                break;
            }
            hits.pollFirst();
        }
    }

    private boolean evictExpiredKeyCooldowns(long now) {
        boolean removedAny = false;
        for (Map.Entry<Integer, Long> entry : keyRateLimitedUntilEpochMs.entrySet()) {
            Long cooldownUntil = entry.getValue();
            if (cooldownUntil != null && cooldownUntil <= now) {
                removedAny |= clearKeyRateLimitState(entry.getKey(), cooldownUntil);
            }
        }
        return removedAny;
    }

    private boolean isKeyRateLimitedAt(int keyIndex, long now) {
        Long cooldownUntil = keyRateLimitedUntilEpochMs.get(keyIndex);
        return cooldownUntil != null && cooldownUntil > now;
    }

    private boolean isKeyAuthenticationFailedAt(int keyIndex) {
        return keyAuthenticationFailedIndices.contains(keyIndex);
    }

    private void evictOutOfRangeKeyState(int keyCount) {
        keyRateLimitedUntilEpochMs.keySet().removeIf(index -> index == null || index < 0 || index >= keyCount);
        keyFallbackBackoffAttempts.keySet().removeIf(index -> index == null || index < 0 || index >= keyCount);
        keyAuthenticationFailedIndices.removeIf(index -> index == null || index < 0 || index >= keyCount);
    }

    private void reconfigureAllBuckets() {
        reconfigureBuckets(getModelsAsMap());
    }

    private void reconfigureBuckets(Map<String, AiModelProperties.ModelConfig> models) {
        if (models == null || models.isEmpty()) {
            return;
        }
        int healthyKeys = Math.max(1, countHealthyGroqApiKeys());
        models.forEach((modelId, modelConfig) -> reconfigureBucket(modelId, modelConfig, healthyKeys));
    }

    private void reconfigureBucket(
            String modelId,
            AiModelProperties.ModelConfig modelConfig,
            int healthyKeys) {
        if (modelId == null || modelId.isBlank() || modelConfig == null) {
            return;
        }
        long modelTpm = Math.max(1L, modelConfig.getTpm());
        long newCapacity = modelTpm * (long) Math.max(1, healthyKeys);
        tpmBucket.reconfigure(modelId, newCapacity);
    }

    private long resolveKeyCooldownMs(int keyIndex, Long cooldownMs) {
        if (cooldownMs != null) {
            keyFallbackBackoffAttempts.remove(keyIndex);
            return Math.max(0L, cooldownMs);
        }
        long baseCooldownMs = Math.max(0L, defaultKeyRateLimitWindowMs);
        if (baseCooldownMs <= 0) {
            return 0L;
        }
        int fallbackAttempt = keyFallbackBackoffAttempts.merge(keyIndex, 1, Integer::sum);
        return computeFallbackBackoffMs(baseCooldownMs, fallbackAttempt);
    }

    private long computeFallbackBackoffMs(long baseCooldownMs, int fallbackAttempt) {
        int boundedShift = Math.max(0, Math.min(20, fallbackAttempt - 1));
        long multiplier = 1L << boundedShift;
        long scaledCooldownMs = baseCooldownMs > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE
                : baseCooldownMs * multiplier;
        return Math.min(MAX_KEY_RATE_LIMIT_BACKOFF_MS, scaledCooldownMs);
    }

    private void clearKeyRateLimitState(int keyIndex) {
        keyRateLimitedUntilEpochMs.remove(keyIndex);
        keyFallbackBackoffAttempts.remove(keyIndex);
    }

    private boolean clearKeyRateLimitState(int keyIndex, Long expectedCooldownUntil) {
        boolean removed = keyRateLimitedUntilEpochMs.remove(keyIndex, expectedCooldownUntil);
        if (removed) {
            keyFallbackBackoffAttempts.remove(keyIndex);
        }
        return removed;
    }

    @Transactional
    public AiModelConfig saveModel(AiModelConfig model) {
        modelCache = null;
        AiModelConfig saved = repository.save(model);
        reconfigureAllBuckets();
        return saved;
    }

    @Transactional
    public void deleteModel(String id) {
        modelCache = null;
        repository.deleteById(id);
        reconfigureAllBuckets();
    }

    @Transactional
    public void deleteAll() {
        modelCache = null;
        repository.deleteAll();
        tpmBucket.clear();
    }
}
