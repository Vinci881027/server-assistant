package com.linux.ai.serverassistant.service.command;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared helpers for deterministic routers that need to parse absolute paths from user text.
 *
 * Kept package-private on purpose: routers are internal infrastructure.
 */
abstract class AbstractPathRouter {

    // Absolute path extraction (contiguous non-whitespace starting with /)
    protected static final Pattern ABS_PATH = Pattern.compile("(/[\\w./_-]+)");

    protected String stripTrailingPunctuation(String s) {
        if (s == null) return null;
        return s.replaceAll("[，。！？、,.!?]+$", "");
    }

    protected Optional<String> extractFirstAbsolutePath(String message) {
        if (message == null) return Optional.empty();
        Matcher m = ABS_PATH.matcher(message);
        if (!m.find()) return Optional.empty();
        return Optional.of(stripTrailingPunctuation(m.group(1)));
    }
}

