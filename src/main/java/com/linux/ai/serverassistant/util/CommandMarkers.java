package com.linux.ai.serverassistant.util;

import java.util.Locale;
import java.util.Set;

/**
 * Centralizes all command marker constants and factory methods used across backend services
 * and matched by the frontend's commandMarkers.js.
 *
 * Marker format: [PREFIX:::content:::]
 */
public final class CommandMarkers {

    // ========== Marker Prefixes / Suffixes ==========

    public static final String CMD_PREFIX = "[CMD:::";
    public static final String CONFIRM_CMD_PREFIX = "[CONFIRM_CMD:::";
    public static final String RESOLVED_CMD_PREFIX = "[RESOLVED_CMD:::";
    public static final String CMD_SUFFIX = ":::]";

    public static final String OFFLOAD_JOB_PREFIX = "[OFFLOAD_JOB:::";
    public static final String BG_JOB_PREFIX = "[BG_JOB:::";

    public static final String SECURITY_VIOLATION = "[SECURITY_VIOLATION]";
    public static final Set<String> EXPLICIT_CANCEL_INTENT_KEYWORDS = Set.of(
            "取消",
            "cancel",
            "取消操作",
            "取消指令"
    );
    public static final Set<String> FLOW_CANCEL_INTENT_KEYWORDS = Set.of(
            "取消",
            "cancel",
            "取消操作",
            "取消指令",
            "停止",
            "stop"
    );

    // ========== Factory Methods ==========

    /**
     * Wraps a command in the standard [CMD:::...:::] confirmation marker.
     */
    public static String cmdMarker(String command) {
        return CMD_PREFIX + command + CMD_SUFFIX;
    }

    /**
     * Wraps a command in the [CONFIRM_CMD:::...:::] marker.
     */
    public static String confirmCmdMarker(String command) {
        return CONFIRM_CMD_PREFIX + command + CMD_SUFFIX;
    }

    /**
     * Wraps a resolved command in [RESOLVED_CMD:::status:::command:::] marker.
     * Status should be "confirmed" or "cancelled".
     */
    public static String resolvedCmdMarker(String command, String status) {
        String normalizedStatus = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        String normalizedCommand = command == null ? "" : command.trim();
        return RESOLVED_CMD_PREFIX + normalizedStatus + ":::" + normalizedCommand + CMD_SUFFIX;
    }

    /**
     * Produces the user-facing confirmation prompt shown in chat.
     */
    public static String confirmationPrompt(String command) {
        return "⚠️ 此操作需要確認，請點擊按鈕執行: " + cmdMarker(command);
    }

    /**
     * Returns true when text contains any confirmation marker prefix.
     */
    public static boolean containsCommandMarker(String text) {
        if (text == null) return false;
        return text.contains(CMD_PREFIX) || text.contains(CONFIRM_CMD_PREFIX);
    }

    /**
     * Returns true when message is an explicit cancel intent keyword.
     */
    public static boolean isCancelIntent(String message) {
        if (message == null) return false;
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return EXPLICIT_CANCEL_INTENT_KEYWORDS.contains(normalized);
    }

    /**
     * Returns true when message is a cancel intent keyword allowed in multi-step flows.
     */
    public static boolean isFlowCancelIntent(String message) {
        if (message == null) return false;
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return FLOW_CANCEL_INTENT_KEYWORDS.contains(normalized);
    }

    /**
     * Wraps an offload job ID in the result marker for frontend job tracking.
     */
    public static String offloadJobResult(String jobId) {
        return OFFLOAD_JOB_PREFIX + jobId + CMD_SUFFIX;
    }

    /**
     * Wraps a background job ID in the result marker for frontend job tracking.
     */
    public static String bgJobResult(String jobId) {
        return BG_JOB_PREFIX + jobId + CMD_SUFFIX;
    }

    /**
     * Formats a tool-layer security violation response for the AI model to summarize.
     */
    public static String toolSecurityViolation(String message) {
        return ToolResultUtils.formatExecutionResult(SECURITY_VIOLATION + " " + message);
    }

    private CommandMarkers() {}
}
