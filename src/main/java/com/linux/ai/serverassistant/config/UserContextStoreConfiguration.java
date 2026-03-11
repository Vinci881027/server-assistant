package com.linux.ai.serverassistant.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linux.ai.serverassistant.util.InMemoryUserContextStore;
import com.linux.ai.serverassistant.util.RedisUserContextStore;
import com.linux.ai.serverassistant.util.UserContextStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class UserContextStoreConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.user-context", name = "store", havingValue = "redis")
    UserContextStore redisUserContextStore(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            UserContextStoreProperties properties) {
        return new RedisUserContextStore(
                redisTemplate,
                objectMapper,
                properties.getRedis().getNamespace(),
                properties.getHardTtlMs());
    }

    @Bean
    @ConditionalOnMissingBean(UserContextStore.class)
    UserContextStore inMemoryUserContextStore(UserContextStoreProperties properties) {
        return new InMemoryUserContextStore(
                properties.getTtlMs(),
                properties.getHardTtlMs(),
                properties.getCleanupIntervalMinutes());
    }
}
