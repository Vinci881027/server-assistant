package com.linux.ai.serverassistant.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AdminAuthorizationService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthorizationService.class);
    private static final String ADMIN_GROUP = "sudo";
    private static final long CACHE_TTL_MS = 60_000; // 1 minute

    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    public boolean isAdmin(String username) {
        if (username == null || username.isBlank()) return false;
        CachedResult cachedResult = cache.get(username);
        if (cachedResult != null && !cachedResult.isExpired()) {
            return cachedResult.value;
        }
        CachedResult refreshed = cache.compute(username, (key, existing) -> {
            if (existing != null && !existing.isExpired()) {
                return existing;
            }
            return new CachedResult(checkSudoGroup(key));
        });
        return refreshed != null && refreshed.value;
    }

    private boolean checkSudoGroup(String username) {
        try {
            ProcessBuilder pb = new ProcessBuilder("id", "-nG", username);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.readLine();
            }

            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Timeout checking groups for user: {}", username);
                return false;
            }

            if (process.exitValue() != 0 || output == null) {
                return false;
            }

            return Arrays.asList(output.trim().split("\\s+")).contains(ADMIN_GROUP);
        } catch (Exception e) {
            log.warn("Failed to check sudo group for user: {}", username, e);
            return false;
        }
    }

    private record CachedResult(boolean value, long timestamp) {
        CachedResult(boolean value) {
            this(value, System.currentTimeMillis());
        }

        boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
