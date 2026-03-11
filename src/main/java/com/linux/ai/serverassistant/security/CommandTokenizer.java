package com.linux.ai.serverassistant.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntPredicate;

final class CommandTokenizer {

    List<String> splitByUnquotedPipes(String command) {
        if (command == null) return List.of();
        return splitByUnquoted(command, c -> c == '|', false);
    }

    String[] tokenize(String command) {
        if (command == null) return new String[0];
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return new String[0];
        return splitByUnquoted(trimmed, Character::isWhitespace, true)
                .toArray(new String[0]);
    }

    String normalizeToken(String token, boolean resolvePathBasename) {
        String raw = stripWrappingQuotes(token == null ? "" : token.trim());
        if (raw.isEmpty()) return "";
        if (resolvePathBasename && raw.contains("/")) {
            int idx = raw.lastIndexOf('/');
            if (idx >= 0 && idx < raw.length() - 1) {
                raw = raw.substring(idx + 1);
            }
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    String stripWrappingQuotes(String text) {
        if (text == null || text.length() < 2) return text;
        if ((text.startsWith("'") && text.endsWith("'"))
                || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }
        return text;
    }

    boolean isQuotesBalanced(String command) {
        int singleQuotes = 0;
        int doubleQuotes = 0;
        boolean escaped = false;

        for (char c : command.toCharArray()) {
            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '\'') {
                singleQuotes++;
            } else if (c == '"') {
                doubleQuotes++;
            }
        }

        return singleQuotes % 2 == 0 && doubleQuotes % 2 == 0;
    }

    boolean containsUnquotedHereDocOperator(String command) {
        if (command == null || command.isEmpty()) return false;

        List<String> segments = splitByUnquoted(command, c -> c == '<', false);
        if (segments.size() < 3) {
            return false;
        }

        for (int i = 1; i < segments.size() - 1; i++) {
            if (segments.get(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private List<String> splitByUnquoted(String text, IntPredicate delimiter, boolean skipEmpty) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                current.append(c);
                continue;
            }
            if (delimiter.test(c) && !inSingle && !inDouble) {
                addSplitToken(parts, current, skipEmpty);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }

        addSplitToken(parts, current, skipEmpty);
        return parts;
    }

    private void addSplitToken(List<String> parts, StringBuilder token, boolean skipEmpty) {
        if (!skipEmpty || !token.isEmpty()) {
            parts.add(token.toString());
        }
    }
}
