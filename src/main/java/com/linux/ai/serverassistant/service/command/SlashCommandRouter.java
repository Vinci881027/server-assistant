package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.system.SystemMetricsService;
import com.linux.ai.serverassistant.service.system.GpuStatusService;
import com.linux.ai.serverassistant.service.system.ProcessMonitorService;
import com.linux.ai.serverassistant.service.system.PortStatusService;
import com.linux.ai.serverassistant.service.docker.DockerSnapshotService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.user.UserCommandConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Deterministic router for slash commands.
 *
 * This runs before the AI model, so slash commands are fast, predictable, and
 * won't depend on the LLM calling tools correctly.
 */
@Service
@Order(0)
public class SlashCommandRouter implements DeterministicRouter,
        DeterministicRouter.SlashCommandAware,
        DeterministicRouter.NoAuditLinuxCommandRouter {
    private static final Logger log = LoggerFactory.getLogger(SlashCommandRouter.class);

    private final UserManagementRouter userManagementRouter;
    private final SystemMetricsService systemMetricsService;
    private final GpuStatusService gpuStatusService;
    private final ProcessMonitorService processMonitorService;
    private final DockerSnapshotService dockerSnapshotService;
    private final PortStatusService portStatusService;
    private final AdminAuthorizationService adminAuthorizationService;

    // Formatting constants — kept here because /status and /gpu slash commands also use them
    private static final String OVERVIEW_WARNING_KEY = NaturalLanguageQueryMatcher.OVERVIEW_WARNING_KEY;
    private static final Pattern DISK_LINE = NaturalLanguageQueryMatcher.DISK_LINE;
    private static final Pattern NVIDIA_SMI_HEADER = NaturalLanguageQueryMatcher.NVIDIA_SMI_HEADER;

    private final NaturalLanguageQueryMatcher naturalLanguageQueryMatcher;
    private final SlashCommandRiskyOperationService riskyOperationService;

    @Autowired
    public SlashCommandRouter(UserManagementRouter userManagementRouter,
                              SystemMetricsService systemMetricsService,
                              GpuStatusService gpuStatusService,
                              ProcessMonitorService processMonitorService,
                              DockerSnapshotService dockerSnapshotService,
                              PortStatusService portStatusService,
                              AdminAuthorizationService adminAuthorizationService,
                              SlashCommandRiskyOperationService riskyOperationService,
                              NaturalLanguageQueryMatcher naturalLanguageQueryMatcher) {
        this.userManagementRouter = userManagementRouter;
        this.systemMetricsService = systemMetricsService;
        this.gpuStatusService = gpuStatusService;
        this.processMonitorService = processMonitorService;
        this.dockerSnapshotService = dockerSnapshotService;
        this.portStatusService = portStatusService;
        this.adminAuthorizationService = adminAuthorizationService;
        this.riskyOperationService = riskyOperationService;
        this.naturalLanguageQueryMatcher = naturalLanguageQueryMatcher;
    }

    @Override
    public Optional<Route> route(Context ctx) {
        if (ctx == null || ctx.message() == null) return Optional.empty();

        String raw = ctx.message().trim();
        if (raw.isEmpty()) return Optional.empty();
        if (raw.startsWith("!")) return Optional.empty();

        // Natural language queries → shortcut routing (bypass AI)
        if (!raw.startsWith("/")) return naturalLanguageQueryMatcher.match(raw, ctx.username());

        ParsedSlash parsed = parse(raw);
        String cmd = parsed.command().toLowerCase(Locale.ROOT);
        boolean isAdmin = adminAuthorizationService.isAdmin(ctx.username());

        // "/" alone -> show help
        if (cmd.isEmpty() || cmd.equals("?") || cmd.equals("help")) {
            return Optional.of(new AssistantText(helpText(isAdmin)));
        }

        String conversationId = ctx.conversationId();
        String sessionUser = (ctx.username() == null || ctx.username().isBlank()) ? "anonymous" : ctx.username();

        if (cmd.equals("adduser") || cmd.equals("add_user") || cmd.equals("createuser") || cmd.equals("create_user")) {
            if (!isAdmin) {
                return Optional.of(new AssistantText("權限不足：`/addUser` 僅限管理員使用。"));
            }
            String msg = buildUserManagementMessage("增加使用者", parsed.args());
            UserManagementRouter.RouteResult rr = userManagementRouter.tryRoute(msg, conversationId, sessionUser);
            return Optional.of(new AssistantText(rr.matched() ? rr.response() : "指令格式：`/addUser [username]`"));
        }

        if (cmd.equals("addsshkey") || cmd.equals("add_ssh_key") || cmd.equals("addssh") || cmd.equals("sshkey")) {
            if (!isAdmin) {
                return Optional.of(new AssistantText("權限不足：`/addSSHKey` 僅限管理員使用。"));
            }
            String msg = buildUserManagementMessage("幫既有使用者加 SSH key", parsed.args());
            UserManagementRouter.RouteResult rr = userManagementRouter.tryRoute(msg, conversationId, sessionUser);
            return Optional.of(new AssistantText(rr.matched() ? rr.response() : "指令格式：`/addSSHKey [username]`"));
        }

        if (cmd.equals("user") || cmd.equals("users") || cmd.equals("listusers") || cmd.equals("list_users")) {
            if (!isAdmin) {
                return Optional.of(new AssistantText("權限不足：`/users` 僅限管理員使用。"));
            }
            return Optional.of(new LinuxCommand(UserCommandConstants.LIST_LOGIN_USERS_COMMAND, false, "系統使用者：\n"));
        }

        if (cmd.equals("gpu") || cmd.equals("gpustatus") || cmd.equals("gpu_status")) {
            String gpuRaw = gpuStatusService.getGpuStatus();
            return Optional.of(new AssistantText(formatGpuStatusAsMarkdown(gpuRaw)));
        }

        if (cmd.equals("top")) {
            String args = parsed.args() == null ? "" : parsed.args().trim();
            if (args.isEmpty()) {
                return Optional.of(new AssistantText("""
                        **/top**
                        - `/top cpu [limit]`（limit: 1-30，預設 5）
                        - `/top mem [limit]`（limit: 1-30，預設 5）
                        """.trim()));
            }

            String[] parts = args.split("\\s+");
            String mode = parts[0].toLowerCase(Locale.ROOT);
            int limit = 5;
            if (parts.length >= 2) {
                try {
                    limit = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
            if (limit < 1) limit = 1;
            if (limit > 30) limit = 30;

            if (mode.equals("cpu")) {
                return Optional.of(new AssistantText(formatTopAsMarkdown("**Top CPU**", processMonitorService.getTopCpuProcessInfos(limit))));
            }
            if (mode.equals("mem")) {
                return Optional.of(new AssistantText(formatTopAsMarkdown("**Top Memory**", processMonitorService.getTopMemoryProcessInfos(limit))));
            }
            return Optional.of(new AssistantText("指令格式：`/top cpu [limit]` 或 `/top mem [limit]`"));
        }

        if (cmd.equals("docker")) {
            String args = parsed.args() == null ? "" : parsed.args().trim();
            return routeDockerSlash(args);
        }

        if (cmd.equals("port") || cmd.equals("ports")) {
            return Optional.of(new AssistantText(formatPortsAsMarkdown()));
        }

        if (cmd.equals("mount")) {
            if (!isAdmin) {
                return Optional.of(new AssistantText("權限不足：`/mount` 僅限管理員使用。"));
            }
            return Optional.of(new AssistantText(riskyOperationService.handleMount(parsed.args(), sessionUser)));
        }

        if (cmd.equals("offload")) {
            if (!isAdmin) {
                return Optional.of(new AssistantText("權限不足：`/offload` 僅限管理員使用。"));
            }
            return Optional.of(new AssistantText(riskyOperationService.handleOffload(parsed.args(), sessionUser)));
        }

        if (cmd.equals("status") || cmd.equals("systemstatus") || cmd.equals("system_status")) {
            Map<String, Object> overview = getSystemOverviewSafely();
            return Optional.of(new AssistantText(formatSystemOverviewAsMarkdown(overview)));
        }

        return Optional.of(new AssistantText(
                "未知的 slash command：`/" + parsed.command() + "`\n\n" +
                        helpText(isAdmin)
        ));
    }

    private Optional<Route> routeDockerSlash(String argsRaw) {
        String args = argsRaw == null ? "" : argsRaw.trim();
        if (args.isEmpty()) {
            return Optional.of(new AssistantText(formatDockerSnapshotAsMarkdown()));
        }
        return Optional.of(new AssistantText("""
                指令格式錯誤：`/docker` 不接受參數。
                請直接使用：`/docker`
                """.trim()));
    }

    private record ParsedSlash(String command, String args) {}

    private ParsedSlash parse(String raw) {
        // raw starts with "/"
        String body = raw.substring(1).trim();
        if (body.isEmpty()) return new ParsedSlash("", "");

        int ws = indexOfWhitespace(body);
        if (ws < 0) return new ParsedSlash(body, "");

        String cmd = body.substring(0, ws).trim();
        String args = body.substring(ws).trim();
        return new ParsedSlash(cmd, args);
    }

    private int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private String buildUserManagementMessage(String prefix, String args) {
        if (args == null || args.isBlank()) return prefix;

        // If user passed a bare username, label it to help the router extract it reliably.
        String trimmed = args.trim();
        if (trimmed.matches("^[a-z_][a-z0-9_-]*$")) {
            return prefix + " username: " + trimmed;
        }
        return prefix + " " + trimmed;
    }

    private Map<String, Object> getSystemOverviewSafely() {
        try {
            Map<String, Object> overview = systemMetricsService != null ? systemMetricsService.getSystemOverview() : null;
            if (overview != null) return overview;
        } catch (Exception ex) {
            log.warn("Failed to get system overview in slash router: {}", ex.getMessage());
        }
        return Map.of(
                "timestamp", Instant.now().toString(),
                OVERVIEW_WARNING_KEY, "系統狀態暫時無法讀取，請稍後再試"
        );
    }

    private String helpText(boolean isAdmin) {
        // Keep it concise; this is shown in chat UI (Markdown supported).
        StringBuilder sb = new StringBuilder("""
                **網站功能**
                - 對話式操作伺服器（可切換模型）
                - 伺服器資訊查詢：CPU/記憶體/磁碟、GPU 狀態、Docker 狀態等
                - 檔案/目錄操作：列目錄、讀檔、寫檔、建立資料夾
                - 系統管理：使用者管理、SSH key 管理與磁碟掛載
                - 高風險指令會彈出確認卡片（按鈕確認/取消後由後端直接執行）

                **Slash Commands**
                - `/docker`：Docker 狀態（容器列表、資源使用）
                - `/gpu`：顯示 GPU 狀態
                - `/port`：列出目前監聽中的 Port（TCP/UDP）
                - `/status`：顯示系統狀態（由後端直接讀取）
                - `/top cpu|mem [limit]`：顯示高耗能進程（CPU 或記憶體）。limit: 1-30，預設 5
                - `/help`：列出可用指令與網站功能
                """);

        if (isAdmin) {
            sb.append("""

                    **Slash Commands（僅限管理員）**
                    - `/addSSHKey [username]`：為既有使用者加入 SSH public key
                    - `/addUser [username]`：新增使用者（互動式：依序詢問 username/password/SSH key）
                    - `/mount`：磁碟格式化 + 寫入 fstab + 掛載（TYPE=disk）
                    - `/offload`：將資料夾複製到其他硬碟後建立 symlink
                    - `/users`：列出系統可登入使用者
                    """);
        }

        sb.append("""

                **Exclamation Commands**
                - `!<linux command>`：直接執行 Linux 指令（繞過 AI，例如 `!docker ps`）
                - 高風險命令仍會進入確認流程
                """);
        return sb.toString();
    }

    private String formatSystemOverviewAsMarkdown(Map<String, Object> overview) {
        return SlashCommandFormattingUtils.formatSystemOverviewAsMarkdown(overview, OVERVIEW_WARNING_KEY, DISK_LINE);
    }

    private String formatTopAsMarkdown(String title, List<Map<String, Object>> rows) {
        return SlashCommandFormattingUtils.formatTopAsMarkdown(title, rows);
    }

    private String formatDockerSnapshotAsMarkdown() {
        Map<String, Object> snap = (dockerSnapshotService != null) ? dockerSnapshotService.getSnapshot() : null;
        return SlashCommandFormattingUtils.formatDockerSnapshotAsMarkdown(snap);
    }

    private String formatPortsAsMarkdown() {
        Map<String, Object> snap = (portStatusService != null) ? portStatusService.getListeningPorts() : null;
        return SlashCommandFormattingUtils.formatPortsAsMarkdown(snap);
    }

    private String formatGpuStatusAsMarkdown(String raw) {
        return SlashCommandFormattingUtils.formatGpuStatusAsMarkdown(raw, NVIDIA_SMI_HEADER);
    }

}
