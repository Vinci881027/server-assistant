package com.linux.ai.serverassistant.util;

/**
 * Shared helpers for formatting and unwrapping tool-execution outputs.
 */
public final class ToolResultUtils {

    private static final String TOOL_RESULT_MARKER = "工具執行結果:\n";
    private static final String LEGACY_TOOL_RESULT_MARKER = "Tool Result:\n";
    private static final String SUMMARY_PROMPT = "\n\n(請將上述工具結果整理成自然、精簡的繁體中文回覆給使用者)";
    private static final String SUMMARY_PROMPT_PREFIX = "\n\n(請";
    private static final String LEGACY_SUMMARY_PROMPT_PREFIX = "\n\n(Please";

    private ToolResultUtils() {
    }

    /**
     * Formats output into the standardized tool-result envelope.
     */
    public static String formatExecutionResult(String content) {
        return TOOL_RESULT_MARKER + content + SUMMARY_PROMPT;
    }

    /**
     * Extracts inner content from a single tool-result envelope.
     * Returns empty string when raw is null.
     */
    public static String extractToolResult(String raw) {
        return extractToolResult(raw, "", 1);
    }

    /**
     * Extracts inner content from a single tool-result envelope.
     */
    public static String extractToolResult(String raw, String nullFallback) {
        return extractToolResult(raw, nullFallback, 1);
    }

    /**
     * Extracts inner content from nested tool-result envelopes.
     *
     * @param raw input string
     * @param nullFallback value returned when raw is null
     * @param unwrapLayers maximum nested envelope layers to unwrap (0 means no unwrap)
     */
    public static String extractToolResult(String raw, String nullFallback, int unwrapLayers) {
        if (raw == null) {
            return nullFallback == null ? "" : nullFallback;
        }

        String current = raw;
        int loops = Math.max(0, unwrapLayers);
        for (int i = 0; i < loops; i++) {
            MarkerMatch marker = findToolResultMarker(current);
            if (marker == null) {
                break;
            }
            int start = marker.index() + marker.markerLength();
            int end = findSummaryPromptStart(current, start);
            if (end < 0) {
                end = current.length();
            }
            current = current.substring(start, Math.min(end, current.length())).trim();
        }
        return current.trim();
    }

    private static MarkerMatch findToolResultMarker(String text) {
        int zh = text.indexOf(TOOL_RESULT_MARKER);
        int en = text.indexOf(LEGACY_TOOL_RESULT_MARKER);
        if (zh < 0 && en < 0) {
            return null;
        }
        if (zh >= 0 && (en < 0 || zh <= en)) {
            return new MarkerMatch(zh, TOOL_RESULT_MARKER.length());
        }
        return new MarkerMatch(en, LEGACY_TOOL_RESULT_MARKER.length());
    }

    private static int findSummaryPromptStart(String text, int fromIndex) {
        int zh = text.indexOf(SUMMARY_PROMPT_PREFIX, fromIndex);
        int en = text.indexOf(LEGACY_SUMMARY_PROMPT_PREFIX, fromIndex);
        if (zh < 0) return en;
        if (en < 0) return zh;
        return Math.min(zh, en);
    }

    private record MarkerMatch(int index, int markerLength) {}
}
