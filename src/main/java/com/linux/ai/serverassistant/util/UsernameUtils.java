package com.linux.ai.serverassistant.util;

import java.util.regex.Pattern;

/**
 * Shared username normalization helpers with no runtime side effects.
 */
public final class UsernameUtils {

    private static final Pattern LINUX_USERNAME_PATTERN = Pattern.compile("^[a-z_][a-z0-9_-]*$");

    private UsernameUtils() {
    }

    /**
     * Normalizes username into a non-blank key-safe value.
     *
     * @return trimmed username or {@code "anonymous"} when null/blank
     */
    public static String normalizeUsernameOrAnonymous(String username) {
        if (username == null) {
            return "anonymous";
        }
        String trimmed = username.trim();
        return trimmed.isEmpty() ? "anonymous" : trimmed;
    }

    /**
     * Validates Linux username format.
     *
     * Allowed format: starts with lowercase letter/underscore, followed by lowercase letters,
     * digits, underscores, or dashes.
     */
    public static boolean isValidLinuxUsername(String username) {
        return username != null && LINUX_USERNAME_PATTERN.matcher(username).matches();
    }
}
