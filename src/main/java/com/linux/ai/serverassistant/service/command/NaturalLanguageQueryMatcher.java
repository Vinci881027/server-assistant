package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.docker.DockerSnapshotService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.system.GpuStatusService;
import com.linux.ai.serverassistant.service.system.PortStatusService;
import com.linux.ai.serverassistant.service.system.ProcessMonitorService;
import com.linux.ai.serverassistant.service.system.SystemMetricsService;
import com.linux.ai.serverassistant.service.user.UserCommandConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises short natural-language queries (≤ 40 chars) and routes them to
 * the same handlers used by slash commands, bypassing the AI model.
 *
 * <p>This class owns the NL term lexicon and all query-classification logic.
 * Slash-command dispatch ({@code /status}, {@code /gpu}, …) lives in
 * {@link SlashCommandRouter}.
 */
@Service
class NaturalLanguageQueryMatcher {

    private static final Logger log = LoggerFactory.getLogger(NaturalLanguageQueryMatcher.class);

    static final int NL_QUERY_MAX_LENGTH = 40;

    // Constants shared with SlashCommandRouter for consistent formatting
    static final String OVERVIEW_WARNING_KEY = "statusWarning";
    static final Pattern DISK_LINE = Pattern.compile(
            "^([^:]+):\\s*總共\\s*([0-9.]+)\\s*GB\\s*/\\s*剩餘\\s*([0-9.]+)\\s*GB\\s*\\(([0-9.]+)%\\)\\s*$");
    static final Pattern NVIDIA_SMI_HEADER = Pattern.compile(
            "^NVIDIA-SMI\\s+([^\\s]+)\\s+Driver Version:\\s*([^\\s]+)\\s+CUDA Version:\\s*([^\\s]+).*$",
            Pattern.CASE_INSENSITIVE);

    // --- Natural Language Query Lexicon ---
    private static final Set<String> NL_DOCKER_ENTITY_TERMS = Set.of(
            "docker", "docker-compose", "compose", "容器", "container", "containers");
    private static final Set<String> NL_DOCKER_LIST_QUERY_TERMS = Set.of(
            "查看", "顯示", "列出", "查詢", "查一下");
    private static final Set<String> NL_DOCKER_SNAPSHOT_SIGNAL_TERMS = Set.of(
            "狀態", "狀況", "列表", "清單", "資訊",
            "status", "info", "ps", "list", "running",
            "運行", "運作", "運作中", "在跑", "跑了", "健康", "health");
    private static final Set<String> NL_DOCKER_LIST_TARGET_TERMS = Set.of(
            "容器", "container", "containers", "compose", "docker-compose",
            "服務", "service", "services");
    private static final Set<String> NL_DOCKER_SERVICE_TERMS = Set.of(
            "服務", "service", "services");
    private static final Pattern NL_DOCKER_COUNT_QUESTION_SUFFIX = Pattern.compile(
            "(?:有哪些|有那些|有沒有|多少|幾個)\\s*[?？]*$");
    private static final Pattern NL_DOCKER_ACTION_HINT = Pattern.compile(
            "(?:啟動|停止|重啟|重新啟動|刪除|移除|更新|建立|執行|部署|日誌|log|logs|inspect|attach|kill|restart|start|stop|remove|delete|create|run|exec|pull|push|deploy|build|compose\\s+up|compose\\s+down|compose(?:\\s+[-\\w./:=]+)*\\s+(?:up|down|start|stop|restart|kill|rm))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NL_DOCKER_STRONG_ACTION_HINT = Pattern.compile(
            "(?:啟動|停止|重啟|重新啟動|刪除|移除|建立|新增|創建|日誌|log|logs|inspect|attach|kill|restart|start|stop|remove|delete|create|run|exec|pull|push|build|prune|compose\\s+up|compose\\s+down|compose(?:\\s+[-\\w./:=]+)*\\s+(?:up|down|start|stop|restart|kill|rm))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NL_DOCKER_READONLY_COMMAND = Pattern.compile(
            "\\bdocker\\s+(?:ps|images|stats|image\\s+ls|container\\s+ls|compose\\s+ps)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> NL_STATUS_ENTITY_TERMS = Set.of(
            "系統", "伺服器", "server", "system");
    private static final Set<String> NL_STATUS_INTENT_TERMS = Set.of(
            "狀態", "狀況", "資訊", "監控", "status", "info");

    private static final Set<String> NL_GPU_ENTITY_TERMS = Set.of(
            "gpu", "顯卡", "顯示卡");
    private static final Set<String> NL_GPU_INTENT_TERMS = Set.of(
            "狀態", "狀況", "資訊", "溫度", "使用率", "使用情況", "status", "info",
            "有在", "有人", "正在", "用", "使用", "跑", "執行", "運行");

    private static final Set<String> NL_PORT_ENTITY_TERMS = Set.of(
            "port", "ports", "埠", "端口");
    private static final Set<String> NL_PORT_INTENT_TERMS = Set.of(
            "監聽", "開放", "listening", "狀態", "狀況", "列表");
    private static final Set<String> NL_PORT_PREFIX_TERMS = Set.of(
            "查看", "顯示", "看一下", "看");

    private static final Set<String> NL_USERS_ENTITY_TERMS = Set.of(
            "使用者", "用戶", "帳號", "user", "users", "account", "accounts");
    private static final Set<String> NL_USERS_INTENT_TERMS = Set.of(
            "查看", "顯示", "看一下", "看", "列出", "列表", "清單", "名單",
            "list", "show");
    private static final Set<String> NL_USERS_COMPOUND_TERMS = Set.of(
            "系統使用者", "系統用戶", "系統帳號",
            "system users", "system accounts");
    private static final Set<String> NL_USERS_STANDALONE_TERMS = Set.of(
            "使用者", "用戶", "帳號", "users", "accounts");

    private static final Set<String> NL_TOP_USAGE_HINT_TERMS = Set.of(
            "高耗能", "高負載", "負載高", "最高", "最吃", "最耗", "top");
    private static final Set<String> NL_TOP_INTENT_TERMS = Set.of(
            "高耗能", "高負載", "負載高", "最高", "最吃", "最耗", "top");
    private static final Set<String> NL_TOP_CPU_TERMS = Set.of(
            "cpu", "處理器");
    private static final Set<String> NL_TOP_MEM_TERMS = Set.of(
            "mem", "memory", "記憶體", "內存", "内存");

    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]+(?:[-_][a-z0-9]+)*");
    private static final Pattern NL_TOP_LIMIT_HINT = Pattern.compile(
            "(?:前\\s*|top\\s*)(\\d{1,2})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NL_TOP_LIMIT_PLAIN = Pattern.compile("\\b(\\d{1,2})\\b");

    private final SystemMetricsService systemMetricsService;
    private final GpuStatusService gpuStatusService;
    private final ProcessMonitorService processMonitorService;
    private final DockerSnapshotService dockerSnapshotService;
    private final PortStatusService portStatusService;
    private final AdminAuthorizationService adminAuthorizationService;

    NaturalLanguageQueryMatcher(SystemMetricsService systemMetricsService,
                                GpuStatusService gpuStatusService,
                                ProcessMonitorService processMonitorService,
                                DockerSnapshotService dockerSnapshotService,
                                PortStatusService portStatusService,
                                AdminAuthorizationService adminAuthorizationService) {
        this.systemMetricsService = systemMetricsService;
        this.gpuStatusService = gpuStatusService;
        this.processMonitorService = processMonitorService;
        this.dockerSnapshotService = dockerSnapshotService;
        this.portStatusService = portStatusService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    /**
     * Attempts to match a short NL query and returns the corresponding route.
     * Returns {@link Optional#empty()} if the message should fall through to the next router.
     */
    Optional<DeterministicRouter.Route> match(String raw, String username) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        if (raw.length() > NL_QUERY_MAX_LENGTH) return Optional.empty();

        String text = normalizeText(raw);
        if (text.isEmpty()) return Optional.empty();

        Set<String> asciiWords = extractAsciiWords(text);

        if (isDockerReadOnlyCommandRequest(text)) {
            return Optional.of(new DeterministicRouter.AssistantText(formatDockerSnapshot()));
        }

        if (isDockerSnapshotQuery(text, asciiWords)) {
            return Optional.of(new DeterministicRouter.AssistantText(formatDockerSnapshot()));
        }

        Optional<DeterministicRouter.Route> topRoute = routeNaturalTopQuery(text, asciiWords);
        if (topRoute.isPresent()) return topRoute;

        if (isStatusQuery(text, asciiWords)) {
            return Optional.of(new DeterministicRouter.AssistantText(formatSystemOverview()));
        }

        if (isGpuQuery(text, asciiWords)) {
            return Optional.of(new DeterministicRouter.AssistantText(
                    SlashCommandFormattingUtils.formatGpuStatusAsMarkdown(gpuStatusService.getGpuStatus(), NVIDIA_SMI_HEADER)));
        }

        if (isPortQuery(text, asciiWords)) {
            Map<String, Object> snap = portStatusService != null ? portStatusService.getListeningPorts() : null;
            return Optional.of(new DeterministicRouter.AssistantText(
                    SlashCommandFormattingUtils.formatPortsAsMarkdown(snap)));
        }

        if (isUsersQuery(text, asciiWords)) {
            if (!adminAuthorizationService.isAdmin(username)) {
                return Optional.of(new DeterministicRouter.AssistantText("權限不足：`/users` 僅限管理員使用。"));
            }
            return Optional.of(new DeterministicRouter.LinuxCommand(
                    UserCommandConstants.LIST_LOGIN_USERS_COMMAND, false, "系統使用者：\n"));
        }

        if (isTopUsageHintQuery(text, asciiWords)) {
            return Optional.of(new DeterministicRouter.AssistantText(topUsageHintText()));
        }

        return Optional.empty();
    }

    // --- Query classification ---

    private boolean isDockerReadOnlyCommandRequest(String text) {
        if (text.isEmpty()) return false;
        if (!NL_DOCKER_READONLY_COMMAND.matcher(text).find()) return false;
        return !NL_DOCKER_STRONG_ACTION_HINT.matcher(text).find();
    }

    private boolean isDockerSnapshotQuery(String text, Set<String> asciiWords) {
        if (text.isEmpty()) return false;
        if (!containsAnyTerm(text, asciiWords, NL_DOCKER_ENTITY_TERMS)) return false;
        if (NL_DOCKER_ACTION_HINT.matcher(text).find()) return false;
        if (containsAnyTerm(text, asciiWords, NL_DOCKER_SNAPSHOT_SIGNAL_TERMS)) return true;
        if (containsAnyTerm(text, asciiWords, NL_DOCKER_LIST_QUERY_TERMS)
                && containsAnyTerm(text, asciiWords, NL_DOCKER_LIST_TARGET_TERMS)) return true;
        return containsAnyTerm(text, asciiWords, NL_DOCKER_SERVICE_TERMS)
                && NL_DOCKER_COUNT_QUESTION_SUFFIX.matcher(text).find();
    }

    private boolean isStatusQuery(String text, Set<String> asciiWords) {
        if (!containsAnyTerm(text, asciiWords, NL_STATUS_ENTITY_TERMS)) return false;
        if (!containsAnyTerm(text, asciiWords, NL_STATUS_INTENT_TERMS)) return false;
        // Avoid swallowing more specific intents
        return !containsAnyTerm(text, asciiWords, NL_DOCKER_ENTITY_TERMS)
                && !containsAnyTerm(text, asciiWords, NL_GPU_ENTITY_TERMS)
                && !containsAnyTerm(text, asciiWords, NL_PORT_ENTITY_TERMS)
                && !isUsersQuery(text, asciiWords);
    }

    private boolean isGpuQuery(String text, Set<String> asciiWords) {
        if (!containsAnyTerm(text, asciiWords, NL_GPU_ENTITY_TERMS)) return false;
        return isStandaloneQuery(text, NL_GPU_ENTITY_TERMS)
                || containsAnyTerm(text, asciiWords, NL_GPU_INTENT_TERMS);
    }

    private boolean isPortQuery(String text, Set<String> asciiWords) {
        if (!containsAnyTerm(text, asciiWords, NL_PORT_ENTITY_TERMS)) return false;
        return isStandaloneQuery(text, NL_PORT_ENTITY_TERMS)
                || startsWithAny(text, NL_PORT_PREFIX_TERMS)
                || containsAnyTerm(text, asciiWords, NL_PORT_INTENT_TERMS);
    }

    private boolean isUsersQuery(String text, Set<String> asciiWords) {
        if (!containsAnyTerm(text, asciiWords, NL_USERS_ENTITY_TERMS)) return false;
        return isStandaloneQuery(text, NL_USERS_STANDALONE_TERMS)
                || containsAnyTerm(text, asciiWords, NL_USERS_INTENT_TERMS)
                || containsAnyPhrase(text, NL_USERS_COMPOUND_TERMS);
    }

    private boolean isTopUsageHintQuery(String text, Set<String> asciiWords) {
        return containsAnyTerm(text, asciiWords, NL_TOP_USAGE_HINT_TERMS);
    }

    private Optional<DeterministicRouter.Route> routeNaturalTopQuery(String text, Set<String> asciiWords) {
        if (!containsAnyTerm(text, asciiWords, NL_TOP_INTENT_TERMS)) return Optional.empty();

        boolean cpu = containsAnyTerm(text, asciiWords, NL_TOP_CPU_TERMS);
        boolean mem = containsAnyTerm(text, asciiWords, NL_TOP_MEM_TERMS);
        if (cpu && mem) return Optional.of(new DeterministicRouter.AssistantText(topUsageHintText()));
        if (!cpu && !mem) return Optional.empty();

        int limit = parseTopLimit(text);
        if (cpu) {
            return Optional.of(new DeterministicRouter.AssistantText(
                    SlashCommandFormattingUtils.formatTopAsMarkdown(
                            "**Top CPU**", processMonitorService.getTopCpuProcessInfos(limit))));
        }
        return Optional.of(new DeterministicRouter.AssistantText(
                SlashCommandFormattingUtils.formatTopAsMarkdown(
                        "**Top Memory**", processMonitorService.getTopMemoryProcessInfos(limit))));
    }

    private String topUsageHintText() {
        return """
                **/top**
                - `/top cpu [limit]`（limit: 1-30，預設 5）
                - `/top mem [limit]`（limit: 1-30，預設 5）
                """.trim();
    }

    private int parseTopLimit(String text) {
        int limit = 5;
        Matcher m = NL_TOP_LIMIT_HINT.matcher(text);
        if (!m.find()) {
            m = NL_TOP_LIMIT_PLAIN.matcher(text);
            if (!m.find()) return limit;
        }
        try {
            limit = Integer.parseInt(m.group(1));
        } catch (Exception ignored) {
            return 5;
        }
        if (limit < 1) return 1;
        if (limit > 30) return 30;
        return limit;
    }

    // --- Text utilities ---

    private String normalizeText(String raw) {
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private Set<String> extractAsciiWords(String text) {
        Matcher m = ASCII_WORD.matcher(text);
        Set<String> words = new HashSet<>();
        while (m.find()) words.add(m.group());
        return words;
    }

    private boolean containsAnyTerm(String text, Set<String> asciiWords, Set<String> terms) {
        for (String term : terms) {
            if (isAsciiTerm(term)) {
                if (asciiWords.contains(term)) return true;
            } else if (text.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean startsWithAny(String text, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean containsAnyPhrase(String text, Set<String> phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) return true;
        }
        return false;
    }

    private boolean isStandaloneQuery(String text, Set<String> terms) {
        String normalized = text.replaceAll("[?？]+$", "").trim();
        for (String term : terms) {
            if (normalized.equals(term)) return true;
        }
        return false;
    }

    private boolean isAsciiTerm(String term) {
        for (int i = 0; i < term.length(); i++) {
            char c = term.charAt(i);
            if (!(c <= 127 && (Character.isLetterOrDigit(c) || c == '-' || c == '_'))) return false;
        }
        return true;
    }

    // --- Formatting helpers ---

    private String formatSystemOverview() {
        Map<String, Object> overview;
        try {
            overview = systemMetricsService != null ? systemMetricsService.getSystemOverview() : null;
        } catch (Exception ex) {
            log.warn("Failed to get system overview in NL matcher: {}", ex.getMessage());
            overview = null;
        }
        if (overview == null) {
            overview = Map.of(
                    "timestamp", Instant.now().toString(),
                    OVERVIEW_WARNING_KEY, "系統狀態暫時無法讀取，請稍後再試"
            );
        }
        return SlashCommandFormattingUtils.formatSystemOverviewAsMarkdown(overview, OVERVIEW_WARNING_KEY, DISK_LINE);
    }

    private String formatDockerSnapshot() {
        Map<String, Object> snap = dockerSnapshotService != null ? dockerSnapshotService.getSnapshot() : null;
        return SlashCommandFormattingUtils.formatDockerSnapshotAsMarkdown(snap);
    }
}
