package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.user.UserManagementService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.linux.ai.serverassistant.util.ToolResultUtils.extractToolResult;

/**
 * Deterministic multi-step router for user management flows.
 */
@Service
@Order(10)
public class UserManagementRouter implements DeterministicRouter, DeterministicRouter.ConversationStateCleaner {

    private final UserManagementService userManagementService;
    private final AdminAuthorizationService adminAuthorizationService;

    public UserManagementRouter(UserManagementService userManagementService,
                                AdminAuthorizationService adminAuthorizationService) {
        this.userManagementService = userManagementService;
        this.adminAuthorizationService = adminAuthorizationService;
    }

    public record RouteResult(boolean matched, String response) {}

    private enum FlowType {
        CREATE_USER,
        ADD_SSH_KEY_ONLY,
        DELETE_USER
    }

    private enum Step {
        WAIT_USERNAME,
        WAIT_PASSWORD,
        WAIT_SSH_KEY
    }

    private static class FlowState {
        private final FlowType flowType;
        private Step step;
        private String username;
        private long updatedAt;

        FlowState(Step step, FlowType flowType) {
            this.flowType = flowType;
            this.step = step;
            this.updatedAt = System.currentTimeMillis();
        }

        void touch() {
            this.updatedAt = System.currentTimeMillis();
        }
    }

    private final Map<String, FlowState> activeFlows = new ConcurrentHashMap<>();
    private static final long FLOW_TTL_MS = 30 * 60 * 1000L;
    private static final int MAX_ACTIVE_FLOWS = 2000;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private static final Pattern START_CREATE_USER_PATTERN = Pattern.compile(
        "(新增|增加|建立|創建|创建).{0,8}(使用者|用户|帳號|账号)|add\\s+user|create\\s+user",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern START_ADD_SSH_KEY_PATTERN = Pattern.compile(
        // Allow longer free text between action/username and "ssh key" (e.g. "新增使用者 xxx 的 ssh key")
        "(新增|增加|加上|加入|設定|添加|設置|配置|add|set).{0,80}(ssh|SSH).{0,20}(key|金鑰|公鑰)|" +
        "(ssh|SSH).{0,20}(key|金鑰|公鑰).{0,80}(新增|增加|加上|加入|設定|添加|設置|配置|add|set)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern START_DELETE_USER_PATTERN = Pattern.compile(
        "(刪除|删除|移除|remove|delete).{0,80}(使用者|用户|帳號|账号|user)|" +
        "(使用者|用户|帳號|账号|user).{0,80}(刪除|删除|移除|remove|delete)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SKIP_PATTERN = Pattern.compile(
        "^(略過|跳過|不需要|不用|skip|none|no|不要)$", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern USERNAME_LABEL = Pattern.compile(
        "(?:username|user|使用者|用户|帳號|账号)\\s*[:：=]\\s*([a-z_][a-z0-9_-]*)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern USERNAME_INLINE = Pattern.compile(
        "(?:給|為|幫|帮|for|to|user|username|使用者|用户|帳號|账号)\\s*[:：=]?\\s*([a-z_][a-z0-9_-]*)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern DELETE_USER_INLINE = Pattern.compile(
        "(?:刪除|删除|移除|remove|delete)\\s*(?:使用者|用户|帳號|账号|user)?\\s*([a-z_][a-z0-9_-]*)",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PASSWORD_LABEL = Pattern.compile(
        "(?:password|密碼|密码)\\s*[:：=]\\s*(.+)$",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern SSH_KEY_INLINE = Pattern.compile(
        "(ssh-rsa|ssh-ed25519|ecdsa-sha2-nistp256|ssh-dss)[ \\t]+[A-Za-z0-9+/=]+([ \\t]+[A-Za-z0-9._@-]+)?$",
        Pattern.CASE_INSENSITIVE
    );

    @Override
    public Optional<Route> route(Context ctx) {
        String msg = (ctx != null) ? ctx.message() : null;
        String convId = (ctx != null) ? ctx.conversationId() : null;
        String user = (ctx != null && ctx.username() != null) ? ctx.username() : "anonymous";
        RouteResult rr = tryRoute(msg, convId, user);
        if (!rr.matched()) return Optional.empty();
        return Optional.of(new AssistantText(rr.response()));
    }

    public RouteResult tryRoute(String message, String conversationId, String sessionUser) {
        if (message == null || message.isBlank()) {
            return new RouteResult(false, null);
        }

        cleanupExpiredFlows();
        String key = buildFlowKey(conversationId, sessionUser);
        cleanupExpiredFlow(key);

        FlowState flow = activeFlows.get(key);
        boolean isAdmin = adminAuthorizationService.isAdmin(sessionUser);

        if (flow != null && isCancelMessage(message)) {
            activeFlows.remove(key);
            return new RouteResult(true, "已取消目前流程。若要重新開始，請再輸入「增加使用者」、「幫既有使用者加 SSH key」或「刪除使用者」。");
        }

        if (!isAdmin) {
            boolean looksLikeUserManagementIntent = flow != null
                    || isCreateUserIntent(message)
                    || isAddSshKeyIntent(message)
                    || isDeleteUserIntent(message);
            if (looksLikeUserManagementIntent) {
                if (flow != null) {
                    activeFlows.remove(key);
                }
                return new RouteResult(true, "權限不足：使用者管理與 SSH key 管理僅限管理員操作。");
            }
        }

        if (flow == null && isDeleteUserIntent(message)) {
            String username = extractUsernameFromDeleteIntent(message);
            if (username != null && username.matches("^[a-z_][a-z0-9_-]*$")) {
                String deletePrompt = userManagementService.manageUsers("delete", username, null, false, sessionUser);
                return new RouteResult(true, extractToolResult(deletePrompt, "", 3));
            }

            putFlow(key, new FlowState(Step.WAIT_USERNAME, FlowType.DELETE_USER));
            return new RouteResult(true, "請提供要刪除的 `username`。");
        }

        if (flow == null && isAddSshKeyIntent(message)) {
            String username = extractUsername(message);
            if (username != null && username.matches("^[a-z_][a-z0-9_-]*$")) {
                FlowState sshFlow = new FlowState(Step.WAIT_SSH_KEY, FlowType.ADD_SSH_KEY_ONLY);
                sshFlow.username = username;
                putFlow(key, sshFlow);
                return new RouteResult(true, "請提供要加入給使用者 `" + username + "` 的 SSH public key。");
            }

            putFlow(key, new FlowState(Step.WAIT_USERNAME, FlowType.ADD_SSH_KEY_ONLY));
            return new RouteResult(true, "請提供要設定 SSH key 的 `username`。");
        }

        if (flow == null && isCreateUserIntent(message)) {
            String username = extractUsername(message);
            if (username != null && username.matches("^[a-z_][a-z0-9_-]*$")) {
                FlowState createFlow = new FlowState(Step.WAIT_PASSWORD, FlowType.CREATE_USER);
                createFlow.username = username;
                putFlow(key, createFlow);
                return new RouteResult(true, "收到要建立的使用者 `"
                        + username + "`。\n請提供此使用者的 `password`。若不設定密碼，請輸入 `略過`。");
            }

            putFlow(key, new FlowState(Step.WAIT_USERNAME, FlowType.CREATE_USER));
            return new RouteResult(true, "請提供要建立的 `username`（僅允許小寫英數、`_`、`-`，且需以字母或 `_` 開頭）。");
        }

        if (flow == null) {
            return new RouteResult(false, null);
        }

        flow.touch();
        return switch (flow.step) {
            case WAIT_USERNAME -> handleUsernameStep(message, key, flow, sessionUser);
            case WAIT_PASSWORD -> handlePasswordStep(message, key, flow, sessionUser);
            case WAIT_SSH_KEY -> handleSshKeyStep(message, key, flow, sessionUser);
        };
    }

    public void clearFlow(String conversationId, String sessionUser) {
        activeFlows.remove(buildFlowKey(conversationId, sessionUser));
    }

    @Override
    public void clearConversationState(String conversationId, String username) {
        clearFlow(conversationId, username);
    }

    private RouteResult handleUsernameStep(String message, String key, FlowState flow, String sessionUser) {
        String username = extractUsername(message);
        if (username == null || username.isBlank()) {
            String prompt = switch (flow.flowType) {
                case ADD_SSH_KEY_ONLY -> "請輸入要設定 SSH key 的 `username`，例如：`devops_user`。";
                case DELETE_USER -> "請輸入要刪除的 `username`，例如：`devops_user`。";
                default -> "請輸入有效的 `username`，例如：`devops_user`。";
            };
            return new RouteResult(true, prompt);
        }

        if (!username.matches("^[a-z_][a-z0-9_-]*$")) {
            return new RouteResult(true, "`username` 格式不合法。請使用小寫英數、`_`、`-`，且以字母或 `_` 開頭。");
        }

        if (flow.flowType == FlowType.DELETE_USER) {
            activeFlows.remove(key);
            String deletePrompt = userManagementService.manageUsers("delete", username, null, false, sessionUser);
            return new RouteResult(true, extractToolResult(deletePrompt, "", 3));
        }

        flow.username = username;
        flow.step = (flow.flowType == FlowType.ADD_SSH_KEY_ONLY) ? Step.WAIT_SSH_KEY : Step.WAIT_PASSWORD;
        flow.touch();
        putFlow(key, flow);

        String prompt = flow.flowType == FlowType.ADD_SSH_KEY_ONLY
            ? "請提供要加入給使用者 `" + flow.username + "` 的 SSH public key。"
            : "請提供此使用者的 `password`。若不設定密碼，請輸入 `略過`。";
        return new RouteResult(true, prompt);
    }

    private RouteResult handlePasswordStep(String message, String key, FlowState flow, String sessionUser) {
        String password = extractPassword(message);
        boolean skipPassword = isSkipMessage(password);

        if (!skipPassword && (password == null || password.isBlank())) {
            return new RouteResult(true, "請提供 `password`。若不設定密碼，請輸入 `略過`。");
        }

        if (!skipPassword && password.contains("'")) {
            return new RouteResult(true, "`password` 不可包含單引號 `'`，請重新輸入。");
        }

        if (!skipPassword && (password.indexOf('\n') >= 0
            || password.indexOf('\r') >= 0
            || password.indexOf('\u0000') >= 0)) {
            return new RouteResult(true, "`password` 不可包含換行、CR 或 NUL 字元，請重新輸入。");
        }

        if (!skipPassword && password.length() > MAX_PASSWORD_LENGTH) {
            return new RouteResult(true, "`password` 長度不可超過 128 個字元，請重新輸入。");
        }

        char[] passwordChars = skipPassword ? null : password.toCharArray();
        String raw = userManagementService.manageUsers("add", flow.username, passwordChars, false, sessionUser);
        String createPrompt = extractToolResult(raw, "", 3);

        if (!CommandMarkers.containsCommandMarker(createPrompt)) {
            activeFlows.remove(key);
            return new RouteResult(
                true,
                "建立使用者失敗：\n" + createPrompt + "\n\n請修正後重新輸入「增加使用者」再試一次。"
            );
        }

        flow.step = Step.WAIT_SSH_KEY;
        flow.touch();
        putFlow(key, flow);

        return new RouteResult(
            true,
            createPrompt + "\n\n請先點擊確認完成建立帳號，完成後再提供 SSH public key；若不需要設定 SSH key，請輸入 `略過`。"
        );
    }

    private RouteResult handleSshKeyStep(String message, String key, FlowState flow, String sessionUser) {
        if (isSkipMessage(message)) {
            activeFlows.remove(key);
            if (flow.flowType == FlowType.ADD_SSH_KEY_ONLY) {
                return new RouteResult(true, "已取消為使用者 `" + flow.username + "` 設定 SSH key。");
            }
            return new RouteResult(true, "使用者 `" + flow.username + "` 已建立，已跳過 SSH key 設定。");
        }

        String publicKey = extractPublicKey(message);
        if (publicKey == null || publicKey.isBlank()) {
            return new RouteResult(true, "請貼上完整 SSH public key。若不需要，請輸入 `略過`。");
        }

        String raw = userManagementService.manageSshKeys("add", flow.username, publicKey, true, sessionUser);
        String sshResult = extractToolResult(raw, "", 3);

        if (sshResult.contains("已成功加入")) {
            activeFlows.remove(key);
            if (flow.flowType == FlowType.ADD_SSH_KEY_ONLY) {
                return new RouteResult(
                    true,
                    "使用者 `" + flow.username + "` 的 SSH key 已成功加入。\n" + sshResult
                );
            }
            return new RouteResult(
                true,
                "使用者 `" + flow.username + "` 建立完成，SSH key 也已加入。\n" + sshResult
            );
        }

        if (isLikelyToolError(sshResult)) {
            return new RouteResult(
                true,
                "SSH key 加入失敗：\n" + sshResult + "\n\n請重新貼上正確的 public key，或輸入 `略過`。"
            );
        }

        activeFlows.remove(key);
        return new RouteResult(
            true,
            (flow.flowType == FlowType.ADD_SSH_KEY_ONLY
                ? "使用者 `" + flow.username + "` SSH key 回應如下：\n"
                : "使用者 `" + flow.username + "` 已建立。SSH key 回應如下：\n") + sshResult
        );
    }

    private boolean isCreateUserIntent(String message) {
        return START_CREATE_USER_PATTERN.matcher(message.trim()).find();
    }

    private boolean isAddSshKeyIntent(String message) {
        return START_ADD_SSH_KEY_PATTERN.matcher(message.trim()).find();
    }

    private boolean isDeleteUserIntent(String message) {
        return START_DELETE_USER_PATTERN.matcher(message.trim()).find();
    }

    private boolean isCancelMessage(String message) {
        return CommandMarkers.isFlowCancelIntent(message);
    }

    private boolean isSkipMessage(String message) {
        if (message == null) return false;
        return SKIP_PATTERN.matcher(message.trim()).matches();
    }

    private String extractUsername(String message) {
        String trimmed = message.trim();

        Matcher labeled = USERNAME_LABEL.matcher(trimmed);
        if (labeled.find()) {
            return labeled.group(1).trim();
        }

        Matcher inline = USERNAME_INLINE.matcher(trimmed);
        if (inline.find()) {
            return inline.group(1).trim();
        }

        String firstToken = trimmed.split("\\s+")[0];
        if (firstToken.matches("^[a-z_][a-z0-9_-]*$")) {
            return firstToken;
        }
        return null;
    }

    private String extractUsernameFromDeleteIntent(String message) {
        String trimmed = message.trim();

        Matcher deleteInline = DELETE_USER_INLINE.matcher(trimmed);
        if (deleteInline.find()) {
            return deleteInline.group(1).trim();
        }

        return extractUsername(trimmed);
    }

    private String extractPassword(String message) {
        String trimmed = message.trim();
        Matcher labeled = PASSWORD_LABEL.matcher(trimmed);
        if (labeled.find()) {
            return labeled.group(1).trim();
        }
        return trimmed;
    }

    private String extractPublicKey(String message) {
        String trimmed = message.trim();

        // Handle fenced code block payloads from chat UIs.
        if (trimmed.startsWith("```") && trimmed.endsWith("```") && trimmed.length() > 6) {
            String body = trimmed.substring(3, trimmed.length() - 3).trim();
            if (body.startsWith("ssh-") || body.startsWith("ecdsa-")) {
                return body;
            }
        }

        Matcher inline = SSH_KEY_INLINE.matcher(trimmed);
        if (inline.find()) {
            return inline.group(0).trim();
        }

        if (trimmed.startsWith("ssh-") || trimmed.startsWith("ecdsa-")) {
            return trimmed;
        }

        return null;
    }

    private boolean isLikelyToolError(String text) {
        String lower = text.toLowerCase();
        return lower.contains("錯誤")
            || lower.contains("[security_violation]")
            || lower.contains("failed")
            || lower.contains("not found")
            || lower.contains("already exists")
            || lower.contains("invalid");
    }

    private String buildFlowKey(String conversationId, String sessionUser) {
        String safeConversationId = (conversationId == null || conversationId.isBlank()) ? "no-conversation" : conversationId;
        String safeUser = (sessionUser == null || sessionUser.isBlank()) ? "anonymous" : sessionUser;
        return safeConversationId + "::" + safeUser;
    }

    private void cleanupExpiredFlow(String key) {
        FlowState flow = activeFlows.get(key);
        if (flow == null) return;
        if (System.currentTimeMillis() - flow.updatedAt > FLOW_TTL_MS) {
            activeFlows.remove(key);
        }
    }

    @Scheduled(fixedRate = 60_000)
    void cleanupExpiredFlows() {
        long now = System.currentTimeMillis();
        activeFlows.entrySet().removeIf(entry -> now - entry.getValue().updatedAt > FLOW_TTL_MS);
    }

    private void putFlow(String key, FlowState flow) {
        if (key == null || flow == null) return;
        cleanupExpiredFlows();
        if (activeFlows.size() >= MAX_ACTIVE_FLOWS) {
            String oldestKey = null;
            long oldestUpdatedAt = Long.MAX_VALUE;
            for (Map.Entry<String, FlowState> entry : activeFlows.entrySet()) {
                long updatedAt = entry.getValue().updatedAt;
                if (updatedAt < oldestUpdatedAt) {
                    oldestUpdatedAt = updatedAt;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey != null) {
                activeFlows.remove(oldestKey);
            }
        }
        activeFlows.put(key, flow);
    }
}
