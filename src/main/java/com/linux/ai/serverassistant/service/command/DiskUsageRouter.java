package com.linux.ai.serverassistant.service.command;

import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic disk-usage interceptor (folder size queries).
 *
 * Motivation: smaller chat models can occasionally return an empty response (no tool call),
 * which results in a bad UX ("AI 未回傳內容"). Folder size queries are common and can be
 * handled deterministically and safely.
 */
@Service
@Order(30)
public class DiskUsageRouter extends AbstractPathRouter implements DeterministicRouter {

    public record RouteResult(boolean matched, String response, String command) {}

    private static final Pattern DELETE_KEYWORD = Pattern.compile(
            "刪除|删除|移除|刪掉|删掉|移掉|remove|delete|\\brm\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SIZE_KEYWORD = Pattern.compile(
            "大小|多大|佔用|占用|空間|空间|size|space|disk\\s*usage", Pattern.CASE_INSENSITIVE);

    private static final Pattern FOLDER_KEYWORD = Pattern.compile(
            "資料夾|文件夹|目錄|目录|folder|directory", Pattern.CASE_INSENSITIVE);

    // Allow direct du commands to bypass the AI as well.
    private static final Pattern DIRECT_DU = Pattern.compile("^\\s*du\\b.*", Pattern.CASE_INSENSITIVE);

    public RouteResult tryRoute(String message) {
        if (message == null || message.isBlank()) return new RouteResult(false, null, null);

        String trimmed = message.trim();

        // Never intercept delete-ish intents.
        if (DELETE_KEYWORD.matcher(trimmed).find()) return new RouteResult(false, null, null);

        // If user directly typed a du command, just execute it deterministically.
        if (DIRECT_DU.matcher(trimmed).matches()) {
            return new RouteResult(true, null, trimmed);
        }

        boolean hasSizeKeyword = SIZE_KEYWORD.matcher(trimmed).find();
        boolean hasFolderKeyword = FOLDER_KEYWORD.matcher(trimmed).find();
        boolean hasAbsolutePath = ABS_PATH.matcher(trimmed).find();

        // Match:
        // - "資料夾大小 /path"
        // - "/path 佔用空間"
        boolean looksLikeFolderSize = hasSizeKeyword && (hasFolderKeyword || hasAbsolutePath);
        if (!looksLikeFolderSize) return new RouteResult(false, null, null);

        Optional<String> pathOpt = extractFirstAbsolutePath(trimmed);
        if (pathOpt.isEmpty()) {
            // Ask for an absolute path deterministically (avoid AI empty response).
            String response = "請提供要查詢大小的資料夾「絕對路徑」。例如：\n" +
                    "- /var/log\n\n" +
                    "你也可以直接輸入：du -ch --max-depth=1 /path | sort -h";
            return new RouteResult(true, response, null);
        }

        String path = pathOpt.get();
        // Default to showing subfolder breakdown as well (sorted by size),
        // because "folder size" questions often need both the total and hotspots.
        // The output is still bounded by CommandExecutionService MAX_OUTPUT_CHARS.
        String cmd = "du -ch --max-depth=1 " + path + " | sort -h";

        return new RouteResult(true, null, cmd);
    }

    @Override
    public Optional<Route> route(Context ctx) {
        RouteResult rr = tryRoute(ctx != null ? ctx.message() : null);
        if (!rr.matched()) return Optional.empty();
        if (rr.response() != null) {
            return Optional.of(new AssistantText(rr.response()));
        }
        if (rr.command() != null && !rr.command().isBlank()) {
            return Optional.of(new LinuxCommand(rr.command(), false, "資料夾大小（已排序）：\n\n"));
        }
        return Optional.of(new AssistantText("⚠️ 無法解析你的請求，請提供要查詢的資料夾路徑，例如：`/var/log`。"));
    }
}
