package com.linux.ai.serverassistant.service.ai;

import com.linux.ai.serverassistant.util.CommandMarkers;

import java.util.regex.Pattern;

/**
 * Static utility class for formatting AI/terminal responses for the chat UI.
 *
 * Extracted from ChatService to keep that class focused on orchestration.
 * All methods are pure functions with no external dependencies.
 */
public final class ResponseFormattingUtils {

    private static final Pattern COMMAND_NOT_FOUND_PATTERN =
            Pattern.compile("\\bcommand\\s+not\\s+found\\b", Pattern.CASE_INSENSITIVE);

    private ResponseFormattingUtils() {}

    // ========== Public API ==========

    /**
     * Formats disk-usage output into a Markdown table for better frontend readability.
     * Applied only to deterministic du commands we generate (max-depth breakdown + sorted).
     */
    public static String maybeFormatDiskUsageAsMarkdownTable(String command, String toolResult) {
        if (toolResult == null) return "";
        if (command == null) return toolResult;

        String c = command.trim();
        if (!c.startsWith("du ")) return toolResult;
        if (!c.contains("--max-depth=1")) return toolResult;
        if (!c.contains("| sort -h")) return toolResult;

        String[] lines = toolResult.split("\\r?\\n");
        StringBuilder table = new StringBuilder();
        table.append("| 大小 | 路徑 |\n");
        table.append("| --- | --- |\n");

        StringBuilder misc = new StringBuilder();
        int rows = 0;

        for (String line : lines) {
            if (line == null) continue;
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            int sep = indexOfWhitespace(trimmed);
            if (sep <= 0 || sep >= trimmed.length() - 1) {
                if (misc.length() > 0) misc.append('\n');
                misc.append(trimmed);
                continue;
            }

            String size = trimmed.substring(0, sep).trim();
            String path = trimmed.substring(sep).trim();
            if (size.isEmpty() || path.isEmpty()) {
                if (misc.length() > 0) misc.append('\n');
                misc.append(trimmed);
                continue;
            }

            table.append("| ")
                    .append(escapeMarkdownTableCell(size))
                    .append(" | ")
                    .append(escapeMarkdownTableCell(path))
                    .append(" |\n");
            rows++;
        }

        if (rows == 0) return toolResult;

        if (misc.length() > 0) {
            table.append("\n其他輸出：\n");
            table.append("```text\n").append(misc).append("\n```");
        }

        return table.toString().trim();
    }

    /**
     * Generic beautifier for raw terminal output.
     *
     * - Keeps [CMD:::...:::] markers intact for frontend action parsing.
     * - Keeps existing Markdown (tables/headings/code fences) unchanged.
     * - Wraps multiline terminal output in a fenced code block to preserve alignment.
     */
    public static String maybeFormatTerminalOutputAsCodeBlock(String output) {
        if (output == null) return "";
        String trimmed = output.trim();
        if (trimmed.isEmpty()) return "";

        if (CommandMarkers.containsCommandMarker(trimmed)) return trimmed;
        if (isLikelyMarkdown(trimmed)) return trimmed;

        int lineCount = (int) trimmed.lines().count();
        boolean multiLine = lineCount >= 4;
        boolean longOutput = trimmed.length() > 600 || lineCount > 12;
        boolean hasAlignedColumns = trimmed.lines().anyMatch(ResponseFormattingUtils::looksLikeAlignedTerminalRow);

        if (!(multiLine && (longOutput || hasAlignedColumns))) {
            return trimmed;
        }

        String fence = trimmed.contains("```") ? "````" : "```";
        return fence + "text\n" + trimmed + "\n" + fence;
    }

    /**
     * Rewrites "command not found" output for exclamation-prefixed commands into a cleaner message.
     */
    public static String maybeRewriteExclamationCommandNotFound(
            String rawMessage, String command, String toolResult) {
        if (toolResult == null) return "";
        if (rawMessage == null || !rawMessage.trim().startsWith("!")) return toolResult;
        if (!isStandaloneCommandNotFoundOutput(toolResult)) return toolResult;

        String token = extractFirstCommandToken(command);
        if (token == null || token.isBlank()) {
            token = command == null ? "" : command.trim();
        }
        if (token.isBlank()) {
            return "找不到指令：`(empty)`";
        }
        return "找不到指令：`" + token + "`";
    }

    // ========== Private Helpers ==========

    private static boolean isStandaloneCommandNotFoundOutput(String toolResult) {
        if (toolResult == null) return false;
        String trimmed = toolResult.trim();
        if (trimmed.isEmpty()) return false;

        int meaningfulLines = 0;
        int notFoundLines = 0;

        for (String rawLine : trimmed.split("\\r?\\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[System Notice]") || line.startsWith("[系統提示]")) continue;

            meaningfulLines++;
            if (COMMAND_NOT_FOUND_PATTERN.matcher(line).find()) {
                notFoundLines++;
                continue;
            }
            return false;
        }

        return meaningfulLines > 0 && notFoundLines > 0;
    }

    private static String extractFirstCommandToken(String command) {
        if (command == null) return null;
        String trimmed = command.trim();
        if (trimmed.isEmpty()) return null;
        String[] parts = trimmed.split("\\s+");
        return parts.length > 0 ? parts[0] : trimmed;
    }

    private static boolean isLikelyMarkdown(String text) {
        if (text.startsWith("```")) return true;
        if (text.startsWith("**")) return true;
        if (text.startsWith("- ") || text.startsWith("* ")) return true;
        if (text.startsWith("| ") && text.contains("\n| ---")) return true;
        if (text.contains("\n```")) return true;
        return false;
    }

    private static boolean looksLikeAlignedTerminalRow(String line) {
        if (line == null) return false;
        String t = line.stripTrailing();
        if (t.isEmpty()) return false;
        return t.indexOf('\t') >= 0 || t.matches(".*\\S\\s{2,}\\S.*");
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    static String escapeMarkdownTableCell(String s) {
        return (s == null) ? "" : s.replace("|", "\\|");
    }
}
