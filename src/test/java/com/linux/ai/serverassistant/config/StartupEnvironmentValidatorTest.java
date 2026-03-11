package com.linux.ai.serverassistant.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartupEnvironmentValidatorTest {

    @Test
    void missingGroqApiKeyShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment();

        StartupEnvironmentValidator validator = newValidator(environment);

        assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
    }

    @Test
    void blankGroqApiKeyShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "   ");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
    }

    @Test
    void unresolvedGroqApiKeyPlaceholderShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "${GROQ_API_KEY}");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
    }

    @Test
    void missingAppEnvAndDeployEnvShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("APP_ENV or DEPLOY_ENV"));
    }

    @Test
    void inconsistentAppEnvAndDeployEnvShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("DEPLOY_ENV", "production");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("APP_ENV and DEPLOY_ENV"));
    }

    @Test
    void equivalentAppEnvAndDeployEnvAliasesShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "prod")
            .withProperty("DEPLOY_ENV", "production")
            .withProperty("app.security.cors.allowed-origins", "https://admin.example.com")
            .withProperty("SESSION_SIGNATURE_SECRET", "session-secret")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void appEnvShouldAllowStartupWhenRequiredSettingsPresent() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "local");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void deployEnvShouldAllowStartupWhenAppEnvMissing() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("DEPLOY_ENV", "local");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void postgresqlWithoutDatabasePasswordShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "local")
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/serverassistant");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
    }

    @Test
    void postgresqlWithDatabasePasswordShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "local")
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void nonPostgresqlWithoutDatabasePasswordShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "local")
            .withProperty("spring.datasource.url", "jdbc:h2:mem:testdb");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void nonLocalPostgresqlWithoutCorsAllowedOriginsShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("SESSION_SIGNATURE_SECRET", "session-secret")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("app.security.cors.allowed-origins"));
    }

    @Test
    void nonLocalPostgresqlWithoutSessionSignatureSecretShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("app.security.cors.allowed-origins", "https://admin.example.com")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("SESSION_SIGNATURE_SECRET"));
    }

    @Test
    void localPostgresqlWithoutCorsAndSessionSecretShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "local")
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void localLikeDevEnvironmentWithoutCorsAndSessionSecretShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "dev")
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void nonLocalPostgresqlWithCorsAndSessionSecretShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("app.security.cors.allowed-origins", "https://admin.example.com")
            .withProperty("SESSION_SIGNATURE_SECRET", "session-secret")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void nonLocalPostgresqlWithBlankCorsPropertyShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("app.security.cors.allowed-origins", " , , ")
            .withProperty("SESSION_SIGNATURE_SECRET", "session-secret")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("app.security.cors.allowed-origins"));
    }

    @Test
    void nonLocalPostgresqlWithUnresolvedCorsPropertyPlaceholderShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("app.security.cors.allowed-origins", "${app.security.cors.allowed-origins}")
            .withProperty("SESSION_SIGNATURE_SECRET", "session-secret")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("app.security.cors.allowed-origins"));
    }

    @Test
    void nonLocalPostgresqlWithUnresolvedSessionSignatureSecretShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("app.security.cors.allowed-origins", "https://admin.example.com")
            .withProperty("SESSION_SIGNATURE_SECRET", "${SESSION_SIGNATURE_SECRET}")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("SESSION_SIGNATURE_SECRET"));
    }

    @Test
    void nonLocalNonPostgresqlWithoutCorsAndSessionSecretShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "staging")
            .withProperty("spring.datasource.url", "jdbc:h2:mem:testdb");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    @Test
    void productionProfileWithoutDatabaseUrlEnvShouldFailStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "local")
            .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");
        environment.setActiveProfiles("prod");

        StartupEnvironmentValidator validator = newValidator(environment);

        IllegalStateException exception = assertThrows(IllegalStateException.class, validator::validateRequiredSettings);
        assertTrue(exception.getMessage().contains("DATABASE_URL"));
    }

    @Test
    void productionProfileWithDatabaseUrlEnvShouldPassStartupValidation() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.openai.api-key", "dummy-key")
            .withProperty("APP_ENV", "local")
            .withProperty("DATABASE_URL", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.url", "jdbc:postgresql://db.example.com:5432/serverassistant")
            .withProperty("spring.datasource.password", "secret");
        environment.setActiveProfiles("production");

        StartupEnvironmentValidator validator = newValidator(environment);

        assertDoesNotThrow(validator::validateRequiredSettings);
    }

    private StartupEnvironmentValidator newValidator(MockEnvironment environment) {
        AiModelProperties properties = new AiModelProperties();
        properties.setDefaultModelKey("20b");
        properties.setDefaultModelName("openai/gpt-oss-20b");
        properties.setModels(Map.of());
        return new StartupEnvironmentValidator(environment, properties);
    }
}
