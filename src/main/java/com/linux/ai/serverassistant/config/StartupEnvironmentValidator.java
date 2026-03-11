package com.linux.ai.serverassistant.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class StartupEnvironmentValidator {
    private static final Logger log = LoggerFactory.getLogger(StartupEnvironmentValidator.class);

    private static final String GROQ_API_KEY_ENV = "GROQ_API_KEY";
    private static final String GROQ_API_KEY_PROPERTY = "spring.ai.openai.api-key";
    private static final String DATABASE_URL_ENV = "DATABASE_URL";
    private static final String DATASOURCE_URL_PROPERTY = "spring.datasource.url";
    private static final String DATASOURCE_PASSWORD_PROPERTY = "spring.datasource.password";
    private static final String APP_ENV = "APP_ENV";
    private static final String DEPLOY_ENV = "DEPLOY_ENV";
    private static final String CORS_ALLOWED_ORIGINS_PROPERTY = "app.security.cors.allowed-origins";
    private static final String SESSION_SIGNATURE_SECRET_ENV = "SESSION_SIGNATURE_SECRET";
    private static final String SESSION_SIGNATURE_SECRET_PROPERTY = "app.security.session.signature-secret";
    private static final String SPRING_PROFILES_ACTIVE_PROPERTY = "spring.profiles.active";
    private static final Set<String> LOCAL_LIKE_ENVIRONMENTS = Set.of("local", "dev", "development", "test");
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private final Environment environment;
    private final AiModelProperties aiModelProperties;

    public StartupEnvironmentValidator(Environment environment, AiModelProperties aiModelProperties) {
        this.environment = environment;
        this.aiModelProperties = aiModelProperties;
    }

    @PostConstruct
    void validateRequiredSettings() {
        String resolvedDeploymentEnvironment = resolveRequiredDeploymentEnvironment();
        validateGroqApiKey();
        validateDatabaseUrlForProduction();
        validatePostgresqlDatabasePassword();
        validateCorsAllowedOriginsForNonLocalPostgresql(resolvedDeploymentEnvironment);
        validateSessionSignatureSecretForNonLocalPostgresql(resolvedDeploymentEnvironment);
        logEffectiveConfigurationSummary(resolvedDeploymentEnvironment);
    }

    private void validateGroqApiKey() {
        String groqApiKeyFromEnv = getPropertySafely(GROQ_API_KEY_ENV);
        if (hasConfiguredGroqKey(groqApiKeyFromEnv)) {
            return;
        }

        String groqApiKeyFromProperty = getPropertySafely(GROQ_API_KEY_PROPERTY);
        if (hasConfiguredGroqKey(groqApiKeyFromProperty)) {
            return;
        }

        throw new IllegalStateException(
            "Missing required primary GROQ API key. Set GROQ_API_KEY (or spring.ai.openai.api-key) before startup. "
                    + "Additional rotation keys can be set via GROQ_API_KEYS (or app.ai.groq-api-keys)."
        );
    }

    private boolean hasConfiguredGroqKey(String configuredValue) {
        if (!StringUtils.hasText(configuredValue)) {
            return false;
        }
        if (isUnresolvedPlaceholder(configuredValue)) {
            return false;
        }
        return Arrays.stream(configuredValue.split(","))
            .map(String::trim)
            .anyMatch(StringUtils::hasText);
    }

    private boolean isUnresolvedPlaceholder(String value) {
        String trimmedValue = value.trim();
        if (!StringUtils.hasText(trimmedValue)) {
            return false;
        }

        return trimmedValue.startsWith("${") && trimmedValue.endsWith("}");
    }

    private void validatePostgresqlDatabasePassword() {
        if (!isPostgresqlDatasourceConfigured()) {
            return;
        }

        String databasePassword = getPropertySafely(DATASOURCE_PASSWORD_PROPERTY);
        if (!StringUtils.hasText(databasePassword) || isUnresolvedPlaceholder(databasePassword)) {
            throw new IllegalStateException(
                "Startup blocked: POSTGRES_PASSWORD (spring.datasource.password) must be set and non-empty."
            );
        }
    }

    private void validateDatabaseUrlForProduction() {
        if (!isProductionProfileActive() || !isPostgresqlDatasourceConfigured()) {
            return;
        }

        String databaseUrlFromEnv = getPropertySafely(DATABASE_URL_ENV);
        if (!StringUtils.hasText(databaseUrlFromEnv) || isUnresolvedPlaceholder(databaseUrlFromEnv)) {
            throw new IllegalStateException(
                "Startup blocked in production: DATABASE_URL must be set and non-empty."
            );
        }
    }

    private boolean isPostgresqlDatasourceConfigured() {
        String datasourceUrl = getPropertySafely(DATASOURCE_URL_PROPERTY);
        if (!StringUtils.hasText(datasourceUrl)) {
            return false;
        }

        return datasourceUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:");
    }

    private void validateCorsAllowedOriginsForNonLocalPostgresql(String deploymentEnvironment) {
        if (!shouldRequireCorsAndSessionSecret(deploymentEnvironment)) {
            return;
        }

        String corsAllowedOriginsFromProperty = getPropertySafely(CORS_ALLOWED_ORIGINS_PROPERTY);
        if (StringUtils.hasText(corsAllowedOriginsFromProperty)
                && hasAtLeastOneConfiguredOrigin(corsAllowedOriginsFromProperty)
                && !isUnresolvedPlaceholder(corsAllowedOriginsFromProperty)) {
            return;
        }

        throw new IllegalStateException(
            "Startup blocked in non-local environment with PostgreSQL datasource: "
                + "app.security.cors.allowed-origins must be set and non-empty."
        );
    }

    private void validateSessionSignatureSecretForNonLocalPostgresql(String deploymentEnvironment) {
        if (!shouldRequireCorsAndSessionSecret(deploymentEnvironment)) {
            return;
        }

        String secretFromEnv = getPropertySafely(SESSION_SIGNATURE_SECRET_ENV);
        if (StringUtils.hasText(secretFromEnv) && !isUnresolvedPlaceholder(secretFromEnv)) {
            return;
        }

        String secretFromProperty = getPropertySafely(SESSION_SIGNATURE_SECRET_PROPERTY);
        if (StringUtils.hasText(secretFromProperty) && !isUnresolvedPlaceholder(secretFromProperty)) {
            return;
        }

        throw new IllegalStateException(
            "Startup blocked in non-local environment with PostgreSQL datasource: "
                + "SESSION_SIGNATURE_SECRET (app.security.session.signature-secret) must be set and non-empty."
        );
    }

    private boolean shouldRequireCorsAndSessionSecret(String deploymentEnvironment) {
        return isPostgresqlDatasourceConfigured() && !LOCAL_LIKE_ENVIRONMENTS.contains(deploymentEnvironment);
    }

    private String resolveRequiredDeploymentEnvironment() {
        String appEnvValue = canonicalizeDeploymentEnvironment(normalizeEnvironmentValue(getPropertySafely(APP_ENV)));
        String deployEnvValue = canonicalizeDeploymentEnvironment(normalizeEnvironmentValue(getPropertySafely(DEPLOY_ENV)));

        if (!StringUtils.hasText(appEnvValue) && !StringUtils.hasText(deployEnvValue)) {
            throw new IllegalStateException("Startup blocked: APP_ENV or DEPLOY_ENV must be set and non-empty.");
        }

        if (StringUtils.hasText(appEnvValue)
                && StringUtils.hasText(deployEnvValue)
                && !appEnvValue.equals(deployEnvValue)) {
            throw new IllegalStateException(
                "Startup blocked: APP_ENV and DEPLOY_ENV are both set but inconsistent."
            );
        }

        return StringUtils.hasText(appEnvValue) ? appEnvValue : deployEnvValue;
    }

    private boolean isProductionProfileActive() {
        Set<String> configuredProfiles = new LinkedHashSet<>();
        configuredProfiles.addAll(normalizeProfiles(Arrays.stream(environment.getActiveProfiles())));

        String configuredProfilesProperty = getPropertySafely(SPRING_PROFILES_ACTIVE_PROPERTY);
        if (StringUtils.hasText(configuredProfilesProperty)) {
            configuredProfiles.addAll(normalizeProfiles(Stream.of(configuredProfilesProperty.split(","))));
        }

        if (configuredProfiles.isEmpty()) {
            return false;
        }

        return configuredProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
    }

    private Set<String> normalizeProfiles(Stream<String> profiles) {
        return profiles
            .map(String::trim)
            .filter(StringUtils::hasText)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String normalizeEnvironmentValue(String value) {
        if (!StringUtils.hasText(value) || isUnresolvedPlaceholder(value)) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String canonicalizeDeploymentEnvironment(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (PRODUCTION_PROFILES.contains(value)) {
            return "production";
        }
        if (LOCAL_LIKE_ENVIRONMENTS.contains(value)) {
            return "local";
        }
        return value;
    }

    private boolean hasAtLeastOneConfiguredOrigin(String configuredOrigins) {
        if (!StringUtils.hasText(configuredOrigins)) {
            return false;
        }

        return Arrays.stream(configuredOrigins.split(","))
            .map(String::trim)
            .anyMatch(StringUtils::hasText);
    }

    private String getPropertySafely(String key) {
        try {
            return environment.getProperty(key);
        } catch (PlaceholderResolutionException ex) {
            return null;
        }
    }

    private void logEffectiveConfigurationSummary(String deploymentEnvironment) {
        String datasourceType = detectDatasourceType(getPropertySafely(DATASOURCE_URL_PROPERTY));
        String defaultModelKey = normalizeSummaryValue(aiModelProperties.getDefaultModelKey());
        String defaultModelName = normalizeSummaryValue(aiModelProperties.getDefaultModelName());
        String modelKeys = formatModelKeys(aiModelProperties.getModels());
        String userContextStore = normalizeSummaryValue(getPropertySafely("app.user-context.store"));
        String userContextNamespace = normalizeSummaryValue(getPropertySafely("app.user-context.redis.namespace"));

        log.info(
            "Startup config summary: env={}, datasource={}, ai.default-model-key={}, ai.default-model-name={}, ai.model-keys={}",
            deploymentEnvironment,
            datasourceType,
            defaultModelKey,
            defaultModelName,
            modelKeys
        );
        log.info(
            "Startup config summary: user-context.store={}, user-context.namespace={}, user-context.bindings=toolSessions+conversationSessions",
            userContextStore,
            userContextNamespace
        );
    }

    private String detectDatasourceType(String datasourceUrl) {
        if (!StringUtils.hasText(datasourceUrl)) {
            return "unknown";
        }

        String normalized = datasourceUrl.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("jdbc:")) {
            return "unknown";
        }

        String remainder = normalized.substring("jdbc:".length());
        int separatorIndex = remainder.indexOf(':');
        if (separatorIndex <= 0) {
            return "unknown";
        }

        return remainder.substring(0, separatorIndex);
    }

    private String normalizeSummaryValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "unset";
        }
        return value.trim();
    }

    private String formatModelKeys(Map<String, AiModelProperties.ModelConfig> models) {
        if (models == null || models.isEmpty()) {
            return "[]";
        }

        return models.keySet().stream()
            .sorted()
            .collect(Collectors.joining(", ", "[", "]"));
    }
}
