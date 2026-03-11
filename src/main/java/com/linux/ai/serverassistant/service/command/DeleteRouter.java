package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.security.CommandValidator;
import com.linux.ai.serverassistant.util.CommandMarkers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic interceptor for delete operations.
 *
 * Pre-processes user messages BEFORE sending to the AI model.
 * For clear delete/rm requests with explicit absolute paths, bypasses the AI
 * entirely and goes directly to the confirmation flow. This prevents the
 * "empty response" bug where smaller models fail to call executeLinuxCommand.
 *
 * Ambiguous or complex requests (no path, query-like messages) fall through
 * to the normal AI flow.
 */
@Service
@Order(20)
public class DeleteRouter extends AbstractPathRouter implements DeterministicRouter {

    private static final Logger log = LoggerFactory.getLogger(DeleteRouter.class);

    private final CommandValidator commandValidator;
    private final CommandConfirmationService commandConfirmationService;

    public DeleteRouter(CommandValidator commandValidator,
                        CommandConfirmationService commandConfirmationService) {
        this.commandValidator = commandValidator;
        this.commandConfirmationService = commandConfirmationService;
    }

    /**
     * Result of attempting to route a user message.
     *
     * @param matched  true if the message was intercepted
     * @param response the response text to stream back (contains [CMD:::] or error)
     * @param command  the rm command (null if not matched or validation failed)
     */
    public record RouteResult(boolean matched, String response, String command) {}

    private record DeleteIntent(String targetPath, boolean isLikelyDirectory) {}

    // ========== Delete keyword patterns ==========

    // Chinese/English delete keywords
    private static final Pattern DELETE_KEYWORD = Pattern.compile(
            "刪除|删除|移除|刪掉|删掉|移掉|remove|delete", Pattern.CASE_INSENSITIVE);

    // Query patterns — should NOT be intercepted (fall through to AI)
    private static final Pattern QUERY_PATTERN = Pattern.compile(
            "有哪些|哪些可以|可以刪除|能刪除|建議刪除|什麼可以|什么可以|" +
            "怎麼刪|怎么删|如何刪除|如何删除|幫我看|帮我看|幫我分析|帮我分析|" +
            "清理.*空間|清理.*空间|列出.*可刪除|列出.*可删除|分析.*佔用|分析.*占用");

    // Direct rm command: rm ... /absolute/path ...
    private static final Pattern DIRECT_RM = Pattern.compile("^\\s*rm\\b");

    // "把 /path 刪掉/刪除/移除" pattern (ba-construction)
    private static final Pattern BA_DELETE = Pattern.compile(
            "把\\s*(/[\\w./_-]+)\\s*(?:刪掉|删掉|移掉|刪除|删除|移除)");

    // ========== Public API ==========

    /**
     * Attempts to intercept a user message and handle it deterministically.
     *
     * @param message  the raw user message
     * @param username the authenticated username (from HttpSession, not ThreadLocal)
     * @return RouteResult with matched=true if intercepted
     */
    public RouteResult tryRoute(String message, String username) {
        if (message == null || message.isBlank()) {
            return new RouteResult(false, null, null);
        }

        String trimmed = message.trim();

        Optional<DeleteIntent> intent = parseDeleteIntent(trimmed);
        if (intent.isPresent()) {
            return handleDeleteIntent(intent.get(), username);
        }

        return new RouteResult(false, null, null);
    }

    @Override
    public Optional<Route> route(Context ctx) {
        String user = (ctx != null && ctx.username() != null) ? ctx.username() : "anonymous";
        RouteResult rr = tryRoute(ctx != null ? ctx.message() : null, user);
        if (!rr.matched()) return Optional.empty();
        return Optional.of(new AssistantText(rr.response()));
    }

    // ========== Delete Intent Parsing ==========

    private Optional<DeleteIntent> parseDeleteIntent(String message) {
        // Reject query patterns first
        if (QUERY_PATTERN.matcher(message).find()) {
            return Optional.empty();
        }

        // Pattern 1: Direct rm command (e.g., "rm -rf ...")
        if (DIRECT_RM.matcher(message).find()) {
            Optional<String> normalizedPath = extractFirstAbsolutePath(message)
                    .flatMap(this::normalizeAbsolutePath);
            if (normalizedPath.isPresent()) {
                boolean hasRecursive = hasRecursiveRmFlag(message);
                return Optional.of(new DeleteIntent(normalizedPath.get(), hasRecursive));
            }
        }

        // Pattern 2: "把 /path 刪掉" (ba-construction)
        Matcher baMatcher = BA_DELETE.matcher(message);
        if (baMatcher.find()) {
            Optional<String> normalizedPath = normalizeAbsolutePath(baMatcher.group(1));
            if (normalizedPath.isPresent()) {
                String path = normalizedPath.get();
                return Optional.of(new DeleteIntent(path, isLikelyDirectory(path)));
            }
        }

        // Pattern 3: Chinese/English keyword + absolute path
        if (!DELETE_KEYWORD.matcher(message).find()) {
            return Optional.empty();
        }

        Optional<String> normalizedPath = extractFirstAbsolutePath(message)
                .flatMap(this::normalizeAbsolutePath);
        if (normalizedPath.isEmpty()) {
            return Optional.empty(); // No absolute path → fall through to AI
        }

        String path = normalizedPath.get();
        return Optional.of(new DeleteIntent(path, isLikelyDirectory(path)));
    }

    // ========== Delete Intent Handling ==========

    private RouteResult handleDeleteIntent(DeleteIntent intent, String username) {
        String path = intent.targetPath();
        String rmCommand = intent.isLikelyDirectory()
                ? "rm -rf " + path
                : "rm " + path;

        // Validate via CommandValidator (checks protected paths, dangerous chars, etc.)
        CommandValidator.ValidationResult validation = commandValidator.validate(rmCommand);

        if (validation instanceof CommandValidator.Invalid invalid) {
            return new RouteResult(true, invalid.errorMessage(), null);
        }

        // Use the same scoped key builder path as CommandExecutionService.
        commandConfirmationService.storePendingCommand(username, rmCommand);

        String prompt = "即將刪除 `" + path + "`\n\n" + CommandMarkers.confirmationPrompt(rmCommand);
        log.debug("DeleteRouter intercepted: {}", rmCommand);
        return new RouteResult(true, prompt, rmCommand);
    }

    // ========== Helpers ==========

    private Optional<String> normalizeAbsolutePath(String rawPath) {
        String stripped = stripTrailingPunctuation(rawPath);
        if (stripped == null || stripped.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Paths.get(stripped).toAbsolutePath().normalize().toString());
        } catch (RuntimeException ex) {
            log.debug("DeleteRouter skipped invalid path candidate: {}", rawPath);
            return Optional.empty();
        }
    }

    private boolean isLikelyDirectory(String path) {
        if (path.endsWith("/")) return true;
        Path fileNamePath = Paths.get(path).getFileName();
        if (fileNamePath == null) return true;
        String fileName = fileNamePath.toString();
        return !fileName.contains(".");
    }

    private boolean hasRecursiveRmFlag(String message) {
        if (message == null) return false;
        String[] tokens = message.trim().split("\\s+");
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if ("--recursive".equals(token)) return true;
            if (token.startsWith("-") && !token.startsWith("--")
                    && (token.indexOf('r') >= 0 || token.indexOf('R') >= 0)) {
                return true;
            }
        }
        return false;
    }
}
