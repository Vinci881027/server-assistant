package com.linux.ai.serverassistant.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

final class PathValidator {

    // System directories that must never be deleted (including descendants)
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/", "/etc", "/bin", "/sbin", "/usr", "/lib", "/lib64",
            "/boot", "/dev", "/proc", "/sys", "/run", "/home", "/root",
            "/var", "/opt", "/tmp"
    );
    // Paths that are protected only at the exact path, not all descendants.
    private static final Set<String> EXACT_MATCH_PROTECTED_PATHS = Set.of(
            "/home"
    );
    private final CommandTokenizer tokenizer;
    private final Set<String> protectedPaths;
    private final Path validationWorkingDirectory;

    PathValidator(CommandTokenizer tokenizer) {
        this(tokenizer, Paths.get("").toAbsolutePath().normalize(), PROTECTED_PATHS);
    }

    PathValidator(CommandTokenizer tokenizer, Path validationWorkingDirectory, Set<String> protectedPaths) {
        this.tokenizer = tokenizer;
        this.validationWorkingDirectory = validationWorkingDirectory.toAbsolutePath().normalize();
        this.protectedPaths = Set.copyOf(protectedPaths);
    }

    /**
     * Checks if an rm/rmdir command targets a protected system path.
     * Returns the protected path if found, or null if safe.
     */
    String checkProtectedPaths(String command, int rmTokenIdx) {
        String[] parts = tokenizer.tokenize(command);
        if (parts.length == 0) return null;

        int start = (rmTokenIdx >= 0) ? rmTokenIdx + 1 : 1;
        for (int i = Math.max(1, start); i < parts.length; i++) {
            String arg = parts[i];
            // Skip flags like -rf, -f, --force
            if (arg.startsWith("-")) continue;
            // Normalize absolute path token and resolve wildcard base,
            // then block both protected paths and their descendants.
            String candidate = normalizeProtectedPathCandidate(arg);
            if (candidate == null) continue;
            String protectedRoot = findProtectedRoot(candidate);
            if (protectedRoot != null) {
                return protectedRoot;
            }

            // Resolve existing ancestor symlinks only when lexical path is not
            // already blocked. This preserves symlink protection while avoiding
            // unconditional filesystem I/O on every command.
            String resolvedCandidate = resolveAgainstExistingAncestor(candidate);
            if (resolvedCandidate == null) continue;
            protectedRoot = findProtectedRoot(resolvedCandidate);
            if (protectedRoot != null) {
                return protectedRoot;
            }
        }
        return null;
    }

    private String normalizeProtectedPathCandidate(String token) {
        if (token == null || token.isBlank()) return null;
        String raw = tokenizer.stripWrappingQuotes(token.trim());
        if (raw == null || raw.isBlank()) return null;
        String globBase = trimToGlobBase(raw);
        String normalized = normalizePathAgainstWorkingDirectory(globBase);
        if (normalized == null || normalized.isBlank()) return null;
        return normalized;
    }

    private String trimToGlobBase(String path) {
        if (path == null || path.isBlank()) return null;
        int wildcardIdx = -1;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                wildcardIdx = i;
                break;
            }
        }
        if (wildcardIdx < 0) return path;

        int slashBefore = path.lastIndexOf('/', wildcardIdx);
        if (slashBefore < 0) return "/";
        if (slashBefore == 0) return "/";
        return path.substring(0, slashBefore);
    }

    private String findProtectedRoot(String candidate) {
        if (candidate == null || !candidate.startsWith("/")) return null;
        if ("/".equals(candidate)) return "/";

        for (String root : protectedPaths) {
            if ("/".equals(root)) continue;
            if (EXACT_MATCH_PROTECTED_PATHS.contains(root)) {
                if (candidate.equals(root)) {
                    return root;
                }
                continue;
            }
            if (candidate.equals(root) || candidate.startsWith(root + "/")) {
                return root;
            }
        }
        return null;
    }

    private String resolveAgainstExistingAncestor(String candidate) {
        if (candidate == null || candidate.isBlank()) return null;
        try {
            Path original = Paths.get(candidate).normalize();
            if (!original.isAbsolute()) return null;

            Path existingAncestor = original;
            while (existingAncestor != null && !Files.exists(existingAncestor)) {
                existingAncestor = existingAncestor.getParent();
            }
            if (existingAncestor == null) return null;

            Path resolvedAncestor = existingAncestor.toRealPath();
            Path resolved = existingAncestor.equals(original)
                    ? resolvedAncestor
                    : resolvedAncestor.resolve(existingAncestor.relativize(original)).normalize();
            String normalized = resolved.toString();
            return normalized.isBlank() ? "/" : normalized;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private String normalizePathAgainstWorkingDirectory(String token) {
        if (token == null || token.isBlank()) return null;
        String raw = tokenizer.stripWrappingQuotes(token.trim());
        if (raw == null || raw.isBlank()) return null;

        try {
            Path input = Paths.get(raw);
            Path normalizedPath = input.isAbsolute()
                    ? input.normalize()
                    : validationWorkingDirectory.resolve(input).normalize();
            if (!normalizedPath.isAbsolute()) return null;

            String normalized = normalizedPath.toString();
            if (normalized.isBlank()) return "/";
            return normalized;
        } catch (Exception ignored) {
            String fallback = raw.endsWith("/") && raw.length() > 1
                    ? raw.substring(0, raw.length() - 1) : raw;
            if (fallback.isBlank()) return "/";
            if (fallback.startsWith("/")) return fallback;
            String base = validationWorkingDirectory.toString();
            if ("/".equals(base)) return "/" + fallback;
            return base + "/" + fallback;
        }
    }
}
