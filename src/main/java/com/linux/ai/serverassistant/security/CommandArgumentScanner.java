package com.linux.ai.serverassistant.security;

import java.util.Locale;
import java.util.Set;

final class CommandArgumentScanner {

    private final CommandTokenizer tokenizer;

    CommandArgumentScanner(CommandTokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    int findNextNonFlagArgument(String[] parts, int start, Set<String> optionsWithValue) {
        if (parts == null || parts.length == 0) return -1;

        Set<String> optionValueSet = optionsWithValue == null ? Set.of() : optionsWithValue;
        int i = Math.max(0, start);
        while (i < parts.length) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isBlank()) {
                i++;
                continue;
            }
            if ("--".equals(raw)) {
                i++;
                continue;
            }

            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-")) {
                if (lower.contains("=")) {
                    i++;
                    continue;
                }
                if (optionValueSet.contains(raw) || optionValueSet.contains(lower)) {
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }
            return i;
        }
        return -1;
    }
}
