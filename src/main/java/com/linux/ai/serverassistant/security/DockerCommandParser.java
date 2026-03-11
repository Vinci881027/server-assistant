package com.linux.ai.serverassistant.security;

import java.util.Locale;
import java.util.Set;

final class DockerCommandParser {

    // Docker commands are treated as mutating by default.
    // Only explicit read-only actions are exempt from confirmation.
    private static final Set<String> DOCKER_READ_ONLY_ACTIONS = Set.of(
            "ps", "images", "stats", "info", "version", "events", "inspect", "logs", "search",
            "container ls", "container ps", "container inspect", "container logs",
            "container top", "container stats", "container port", "container diff",
            "image ls", "image list", "image inspect", "image history",
            "volume ls", "volume inspect",
            "network ls", "network inspect",
            "system df", "system events", "system info",
            "compose ps", "compose ls", "compose logs", "compose top",
            "compose images", "compose config", "compose events"
    );
    private static final Set<String> DOCKER_SUBCOMMANDS_WITH_ACTION = Set.of(
            "container", "image", "volume", "network", "system", "compose"
    );
    private static final Set<String> DOCKER_GLOBAL_OPTIONS_WITH_VALUE = Set.of(
            "-H", "--host", "-c", "--context", "--config", "-l", "--log-level");
    private static final Set<String> DOCKER_COMPOSE_OPTIONS_WITH_VALUE = Set.of(
            "-f", "--file", "-p", "--project-name", "--profile", "--env-file", "--project-directory");

    private final CommandTokenizer tokenizer;
    private final CommandArgumentScanner argumentScanner;

    DockerCommandParser(CommandTokenizer tokenizer, CommandArgumentScanner argumentScanner) {
        this.tokenizer = tokenizer;
        this.argumentScanner = argumentScanner;
    }

    String extractHighRiskAction(String command, int dockerTokenIdx) {
        if (command == null) return null;
        String[] parts = tokenizer.tokenize(command);
        if (parts.length == 0 || dockerTokenIdx < 0 || dockerTokenIdx >= parts.length) return null;

        int subIdx = argumentScanner.findNextNonFlagArgument(parts, dockerTokenIdx + 1, DOCKER_GLOBAL_OPTIONS_WITH_VALUE);
        if (subIdx < 0) return null;

        String sub = normalizeDockerCliToken(parts[subIdx]);
        String actionKey = sub;
        if (DOCKER_SUBCOMMANDS_WITH_ACTION.contains(sub)) {
            Set<String> optionsWithValue = "compose".equals(sub) ? DOCKER_COMPOSE_OPTIONS_WITH_VALUE : Set.of();
            int actionIdx = argumentScanner.findNextNonFlagArgument(parts, subIdx + 1, optionsWithValue);
            if (actionIdx < 0) return null;
            String action = normalizeDockerCliToken(parts[actionIdx]);
            actionKey = sub + " " + action;
        }

        if (DOCKER_READ_ONLY_ACTIONS.contains(actionKey)) {
            return null;
        }
        return actionKey;
    }

    private String normalizeDockerCliToken(String token) {
        String raw = token == null ? "" : token.trim();
        if (raw.isEmpty()) return "";

        raw = tokenizer.stripWrappingQuotes(raw);
        while (raw.startsWith("'") || raw.startsWith("\"") || raw.startsWith("`")) {
            raw = raw.substring(1);
        }
        while (raw.endsWith("'") || raw.endsWith("\"") || raw.endsWith("`")) {
            raw = raw.substring(0, raw.length() - 1);
        }
        if (raw.isEmpty()) return "";
        return raw.toLowerCase(Locale.ROOT);
    }
}
