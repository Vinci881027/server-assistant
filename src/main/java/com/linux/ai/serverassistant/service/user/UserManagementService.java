package com.linux.ai.serverassistant.service.user;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.AuditLogPersistenceService;
import com.linux.ai.serverassistant.service.command.CommandAuditService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService.ExecutionOptions;
import com.linux.ai.serverassistant.service.command.PendingConfirmationManager;
import com.linux.ai.serverassistant.service.command.PendingConfirmationScope;
import com.linux.ai.serverassistant.service.command.SensitivePendingPayload;
import com.linux.ai.serverassistant.service.file.FileOperationService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UsernameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.linux.ai.serverassistant.util.ToolResultUtils.extractToolResult;
import static com.linux.ai.serverassistant.util.ToolResultUtils.formatExecutionResult;

/**
 * User Management Service
 *
 * Provides secure Linux user account and SSH key management capabilities:
 * - User account management (list, create, delete)
 * - Password management (via ProcessBuilder stdin to avoid shell injection)
 * - SSH key management (list, add via file service with symlink protection)
 *
 * Security Features:
 * - Confirmation mechanism for destructive operations (user deletion)
 * - Username and SSH key validation to prevent injection attacks
 * - Audit logging through CommandExecutionService
 * - Password and key content never passed through shell command strings
 * - Automatic home directory and bash shell setup
 *
 * @author Claude Code
 * @version 1.1
 */
@Service
public class UserManagementService {

    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);
    private static final Duration SSH_KEY_FILE_LOCK_TTL = Duration.ofMinutes(30);
    private static final long FORCED_PROCESS_REAP_TIMEOUT_SECONDS = 1;

    // ========== Dependencies ==========

    private final CommandExecutionService commandExecutionService;
    private final CommandLogRepository commandLogRepository;
    private final AdminAuthorizationService adminAuthorizationService;
    private final PendingConfirmationManager pendingConfirmationManager;
    private final FileOperationService fileOperationService;
    private final Cache<String, Object> sshKeyFileLocks = Caffeine.newBuilder()
            .expireAfterAccess(SSH_KEY_FILE_LOCK_TTL)
            .build();

    public record ManagedActionResult(String rawToolResult, boolean success, Integer exitCode) {}

    public static boolean isValidLinuxUsername(String username) {
        return UsernameUtils.isValidLinuxUsername(username);
    }

    // ========== Constructor ==========

    public UserManagementService(CommandExecutionService commandExecutionService,
                                 CommandLogRepository commandLogRepository,
                                 AdminAuthorizationService adminAuthorizationService,
                                 PendingConfirmationManager pendingConfirmationManager,
                                 FileOperationService fileOperationService) {
        this.commandExecutionService = commandExecutionService;
        this.commandLogRepository = commandLogRepository;
        this.adminAuthorizationService = adminAuthorizationService;
        this.pendingConfirmationManager = pendingConfirmationManager;
        this.fileOperationService = fileOperationService;
    }

    // ========== Public API - User Management ==========

    /**
     * Manages Linux user accounts.
     *
     * Supported actions:
 * - list: Lists login-capable users (excludes common nologin/false service accounts)
     * - add: Creates a new user with home directory and bash shell (requires confirmation)
     * - delete: Deletes a user and their home directory (requires confirmation)
     *
     * @param action the user management operation to perform
     * @param username the username for add/delete operations (null for list)
     * @param password the password for user creation (optional, null to skip password setup)
     * @param confirm whether the user has confirmed execution of a high-risk operation
     * @return formatted execution result with command output or error message
     */
    public String manageUsers(String action, String username, char[] password, boolean confirm, String actor) {
        try {
            if (action == null || action.isBlank()) {
                return formatExecutionResult("錯誤：未指定動作 (list/add/delete)。");
            }

            String lowerAction = action.trim().toLowerCase(Locale.ROOT);
            actor = normalizeValidatedActor(actor);
            if (actor == null) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 無法識別目前操作使用者，請重新登入後再試。");
            }
            if (!adminAuthorizationService.isAdmin(actor)) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 權限不足：manageUsers list/add/delete 僅限管理員。");
            }

            if ("list".equals(lowerAction)) {
                // List users: show login-capable accounts while filtering common service shells.
                return commandExecutionService.execute(
                        UserCommandConstants.LIST_LOGIN_USERS_COMMAND,
                        ExecutionOptions.builder()
                                .confirm(false)
                                .user(actor)
                                .build());
            }

            if (username == null || username.isBlank()) {
                return formatExecutionResult("錯誤：必須指定使用者名稱。");
            }

            // Security check: validate username (only allow lowercase letters, numbers, underscore, dash, must start with letter)
            if (!isValidLinuxUsername(username)) {
                return formatExecutionResult("錯誤：使用者名稱格式不合法。");
            }

            if ("add".equals(lowerAction)) {
                if (!confirm) {
                    // ProcessBuilder + stdin to avoid shell injection
                    if (password != null && hasNonBlankPassword(password)) {
                        for (char c : password) {
                            if (c == '\n' || c == '\r' || c == '\0') {
                                return formatExecutionResult("錯誤：密碼不可包含換行或空字元。");
                            }
                        }
                    }
                    String pseudoCommand = buildAddPseudoCommand(username);
                    String prompt = commandExecutionService.execute(
                            pseudoCommand,
                            ExecutionOptions.builder()
                                    .confirm(false)
                                    .user(actor)
                                    .noOptimize()
                                    .noAudit()
                                    .build());
                    if (CommandMarkers.containsCommandMarker(prompt)) {
                        pendingConfirmationManager.storeWithPayload(
                                PendingConfirmationScope.USER_ADD,
                                actor,
                                pseudoCommand,
                                new PendingAddRequest(clonePassword(password)));
                    }
                    return prompt;
                }
                String pseudoCommand = buildAddPseudoCommand(username);
                if (!pendingConfirmationManager.consume(PendingConfirmationScope.CMD, actor, pseudoCommand)) {
                    return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 找不到此新增使用者指令的待確認紀錄，請重新操作。");
                }
                PendingAddRequest pending = pendingConfirmationManager
                        .consumePayload(PendingConfirmationScope.USER_ADD, actor, pseudoCommand, PendingAddRequest.class)
                        .orElse(null);
                if (pending == null) {
                    return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 找不到此新增使用者指令的待確認資料，請重新操作。");
                }
                char[] effectivePassword = clonePassword(pending.password());
                try {
                    boolean hasPassword = hasNonBlankPassword(effectivePassword);

                    // add user
                    String addCmd = buildAddPseudoCommand(username);
                    String addResult = commandExecutionService.execute(
                            addCmd,
                            CommandExecutionService.ExecutionOptions.builder().trustedRoot().user(actor).build());

                    if (isExecutionFailed(addResult)) {
                        return addResult;
                    }

                    if (hasPassword) {
                        String pwdResult = setPasswordViaStdin(username, effectivePassword);
                        if (pwdResult != null) {
                            saveAuditLog("chpasswd (user: " + username + ")", pwdResult, false, actor);
                            String rollbackError = rollbackCreatedUser(username, actor);
                            String rollbackMsg = (rollbackError == null)
                                    ? "已自動回滾（刪除已建立帳號）。"
                                    : "自動回滾失敗，請手動檢查帳號狀態。回滾訊息：" + rollbackError;
                            return formatExecutionResult("錯誤：密碼設定失敗 — " + pwdResult + "。 " + rollbackMsg);
                        }
                        saveAuditLog("chpasswd (user: " + username + ")", "密碼設定成功", true, actor);
                    }

                    // create .ssh directory and authorized_keys
                    String sshSetupResult = setupSshDirectory(username, actor);
                    if (isExecutionFailed(sshSetupResult)) {
                        String rollbackError = rollbackCreatedUser(username, actor);
                        String rollbackMsg = (rollbackError == null)
                                ? "已自動回滾（刪除已建立帳號）。"
                                : "自動回滾失敗，請手動檢查帳號狀態。回滾訊息：" + rollbackError;
                        return formatExecutionResult("錯誤：建立 SSH 目錄失敗 — " + extractToolResult(sshSetupResult) + "。 " + rollbackMsg);
                    }

                    return formatExecutionResult("使用者 " + username + " 已建立"
                        + (hasPassword ? "，密碼已設定" : "") + "。");
                } finally {
                    clearSensitivePassword(effectivePassword);
                    clearSensitivePendingAddRequest(pending);
                }
            }

            if ("delete".equals(lowerAction)) {
                String pseudoCommand = buildDeletePseudoCommand(username);
                if (!confirm) {
                    return commandExecutionService.execute(
                            pseudoCommand,
                            ExecutionOptions.builder()
                                    .confirm(false)
                                    .user(actor)
                                    .noOptimize()
                                    .noAudit()
                                    .build());
                }
                if (!pendingConfirmationManager.consume(PendingConfirmationScope.CMD, actor, pseudoCommand)) {
                    return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 找不到此刪除使用者指令的待確認紀錄，請重新操作。");
                }

                if (!userExists(username, actor)) {
                    return formatExecutionResult("錯誤：使用者 " + username + " 不存在。");
                }
                String deleteResult = commandExecutionService.execute(
                        pseudoCommand,
                        CommandExecutionService.ExecutionOptions.builder().trustedRoot().user(actor).build());
                String deleteContent = extractToolResult(deleteResult);

                if (isExecutionFailed(deleteResult)) {
                    return deleteResult;
                }

                // Benign warning: mail spool missing does not affect successful deletion.
                if (isBenignDeleteWarning(deleteContent)
                        || deleteContent.isBlank()
                        || "（無輸出）".equals(deleteContent)
                        || "(No Output)".equals(deleteContent)) {
                    return formatExecutionResult("使用者 " + username + " 已刪除。");
                }

                return formatExecutionResult("使用者 " + username + " 已刪除。\n" + deleteContent);
            }

            return formatExecutionResult("不支援的操作：" + action + "。目前支援：list, add, delete");
        } finally {
            clearSensitivePassword(password);
        }
    }

    /**
     * Executes confirmed add-user flow and returns structured status.
     */
    public ManagedActionResult executeConfirmedAddUser(String username, String actor) {
        String normalizedActor = normalizeValidatedActor(actor);
        if (normalizedActor == null) {
            return failedResult(
                    formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 無法識別目前操作使用者，請重新登入後再試。"),
                    null);
        }
        if (!adminAuthorizationService.isAdmin(normalizedActor)) {
            return failedResult(
                    formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 權限不足：manageUsers add/delete 僅限管理員。"),
                    null);
        }
        if (username == null || username.isBlank()) {
            return failedResult(formatExecutionResult("錯誤：必須指定使用者名稱。"), null);
        }
        if (!isValidLinuxUsername(username)) {
            return failedResult(formatExecutionResult("錯誤：使用者名稱格式不合法。"), null);
        }
        return executeConfirmedAddUserInternal(username, normalizedActor);
    }

    /**
     * Executes confirmed delete-user flow and returns structured status.
     */
    public ManagedActionResult executeConfirmedDeleteUser(String username, String actor) {
        String normalizedActor = normalizeValidatedActor(actor);
        if (normalizedActor == null) {
            return failedResult(
                    formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 無法識別目前操作使用者，請重新登入後再試。"),
                    null);
        }
        if (!adminAuthorizationService.isAdmin(normalizedActor)) {
            return failedResult(
                    formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 權限不足：manageUsers add/delete 僅限管理員。"),
                    null);
        }
        if (username == null || username.isBlank()) {
            return failedResult(formatExecutionResult("錯誤：必須指定使用者名稱。"), null);
        }
        if (!isValidLinuxUsername(username)) {
            return failedResult(formatExecutionResult("錯誤：使用者名稱格式不合法。"), null);
        }
        return executeConfirmedDeleteUserInternal(username, normalizedActor);
    }

    public void clearPendingAddConfirmation(String username, String rawCommand) {
        if (rawCommand == null || rawCommand.isBlank()) return;
        String command = rawCommand.trim();
        pendingConfirmationManager.clear(PendingConfirmationScope.USER_ADD, username, command);
    }

    public void clearPendingAddConfirmations(String username) {
        pendingConfirmationManager.clearScopesForUser(username, PendingConfirmationScope.USER_ADD);
    }

    // ========== Public API - SSH Key Management ==========

    /**
     * Manages SSH keys for a specified user or the current user.
     *
     * Supported actions:
     * - list: Displays all SSH public keys in authorized_keys
     * - add: Adds a new SSH public key (non-destructive, no confirmation needed)
     *
     * @param action the SSH key management operation to perform
     * @param username target username (null for current user)
     * @param publicKey the SSH public key content (required for add operation)
     * @param confirm whether the user has confirmed execution (unused for add, kept for API compatibility)
     * @return formatted execution result with command output or error message
     */
    public String manageSshKeys(String action, String username, String publicKey, boolean confirm, String actor) {
        if (action == null || action.isBlank()) {
            return formatExecutionResult("錯誤：未指定動作 (list/add)。");
        }

        String lowerAction = action.trim().toLowerCase(Locale.ROOT);
        actor = normalizeValidatedActor(actor);
        if (actor == null) {
            return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 無法識別目前操作使用者，請重新登入後再試。");
        }
        boolean isAdmin = adminAuthorizationService.isAdmin(actor);

        String requestedUser = (username == null) ? null : username.trim();
        if (requestedUser != null && !requestedUser.isBlank() && !requestedUser.equals(actor) && !isAdmin) {
            return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 權限不足：非管理員只能查看或修改自己的 SSH keys。");
        }

        String targetUser = resolveTargetUser(username, actor);
        if (targetUser == null || targetUser.isBlank()) {
            return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 無法識別目前操作使用者，請重新登入或明確指定 username。");
        }

        // Determine target SSH directory
        if (!isValidLinuxUsername(targetUser)) {
            return formatExecutionResult("錯誤：使用者名稱格式不合法。");
        }
        String homeDir = "root".equals(targetUser) ? "/root" : "/home/" + targetUser;
        String sshDir = homeDir + "/.ssh";
        String keysFile = sshDir + "/authorized_keys";

        if ("add".equals(lowerAction)) {
            if (!isAdmin) {
                return formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 權限不足：manageSshKeys add 僅限管理員。");
            }
            if (publicKey == null || publicKey.isBlank()) {
                return formatExecutionResult("錯誤：新增 SSH Key 必須提供公鑰內容。");
            }

            // Check public key format (basic check: must start with ssh- or ecdsa- etc.)
            if (!publicKey.trim().matches("^(ssh-rsa|ssh-ed25519|ecdsa-sha2-nistp256|ssh-dss)[ \\t]+[A-Za-z0-9+/=]+([ \\t]+[A-Za-z0-9._@-]+)?$")) {
                return formatExecutionResult("錯誤：無效的 SSH 公鑰格式。請確保您貼上的是公鑰 (Public Key) 而非私鑰。");
            }

            if (!userExists(targetUser, actor)) {
                return formatExecutionResult("錯誤：使用者 " + targetUser + " 不存在。");
            }

            // 確保 .ssh 目錄與 authorized_keys 存在（既有使用者也適用）
            String sshSetupResult = setupSshDirectory(targetUser, actor);
            if (isExecutionFailed(sshSetupResult)) {
                return sshSetupResult;
            }

            // 透過 FileOperationService 安全寫入 SSH key（含 symlink 防護）
            String writeError = appendSshKeyViaStdin(keysFile, publicKey.trim(), actor);
            if (writeError != null) {
                saveAuditLog("writeFileContent " + keysFile + " (SSH key)", writeError, false, actor);
                return formatExecutionResult("錯誤：SSH Key 寫入失敗 — " + writeError);
            }
            saveAuditLog("writeFileContent " + keysFile + " (SSH key)", "SSH Key 寫入成功", true, actor);

            // 設定正確的權限與 ownership
            String chmodCmd = String.format("chmod 600 %s && chown -R %s:%s %s",
                    keysFile, targetUser, targetUser, sshDir);
            String chmodResult = commandExecutionService.execute(
                    chmodCmd,
                    CommandExecutionService.ExecutionOptions.builder().trustedRoot().user(actor).build());
            if (isExecutionFailed(chmodResult)) {
                return chmodResult;
            }

            return formatExecutionResult("SSH Key 已成功加入");

        } else if ("list".equals(lowerAction)) {
            return commandExecutionService.execute(
                "test -f " + keysFile + " && cat " + keysFile + " || echo '(尚未設定 SSH key)'",
                CommandExecutionService.ExecutionOptions.builder().trustedRoot().user(actor).build()
            );
        } else {
            return formatExecutionResult("不支援的操作：" + action + "。目前支援：list, add");
        }
    }

    // ========== Private Helper Methods ==========

    private ManagedActionResult executeConfirmedAddUserInternal(String username, String actor) {
        String pseudoCommand = buildAddPseudoCommand(username);
        if (!pendingConfirmationManager.consume(PendingConfirmationScope.CMD, actor, pseudoCommand)) {
            return failedResult(
                    formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 找不到此新增使用者指令的待確認紀錄，請重新操作。"),
                    null);
        }
        PendingAddRequest pending = pendingConfirmationManager
                .consumePayload(PendingConfirmationScope.USER_ADD, actor, pseudoCommand, PendingAddRequest.class)
                .orElse(null);
        if (pending == null) {
            return failedResult(
                    formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 找不到此新增使用者指令的待確認資料，請重新操作。"),
                    null);
        }

        char[] effectivePassword = clonePassword(pending.password());
        try {
            boolean hasPassword = hasNonBlankPassword(effectivePassword);
            var addExecution = runTrustedRootWithResult(buildAddPseudoCommand(username), actor);
            if (!addExecution.success()) {
                return failedResult(addExecution.rawToolResult(), addExecution.exitCode());
            }

            if (hasPassword) {
                String pwdError = setPasswordViaStdin(username, effectivePassword);
                if (pwdError != null) {
                    saveAuditLog("chpasswd (user: " + username + ")", pwdError, false, actor);
                    String rollbackError = rollbackCreatedUser(username, actor);
                    String rollbackMsg = (rollbackError == null)
                            ? "已自動回滾（刪除已建立帳號）。"
                            : "自動回滾失敗，請手動檢查帳號狀態。回滾訊息：" + rollbackError;
                    String raw = formatExecutionResult("錯誤：密碼設定失敗 — " + pwdError + "。 " + rollbackMsg);
                    return failedResult(raw, null);
                }
                saveAuditLog("chpasswd (user: " + username + ")", "密碼設定成功", true, actor);
            }

            var sshSetupExecution = setupSshDirectoryWithResult(username, actor);
            if (!sshSetupExecution.success()) {
                String rollbackError = rollbackCreatedUser(username, actor);
                String rollbackMsg = (rollbackError == null)
                        ? "已自動回滾（刪除已建立帳號）。"
                        : "自動回滾失敗，請手動檢查帳號狀態。回滾訊息：" + rollbackError;
                String raw = formatExecutionResult(
                        "錯誤：建立 SSH 目錄失敗 — " + extractToolResult(sshSetupExecution.rawToolResult()) + "。 " + rollbackMsg);
                return failedResult(raw, sshSetupExecution.exitCode());
            }

            String successText = "使用者 " + username + " 已建立"
                    + (hasPassword ? "，密碼已設定" : "") + "。";
            return successResult(formatExecutionResult(successText), 0);
        } finally {
            clearSensitivePassword(effectivePassword);
            clearSensitivePendingAddRequest(pending);
        }
    }

    private ManagedActionResult executeConfirmedDeleteUserInternal(String username, String actor) {
        String pseudoCommand = buildDeletePseudoCommand(username);
        if (!pendingConfirmationManager.consume(PendingConfirmationScope.CMD, actor, pseudoCommand)) {
            return failedResult(
                    formatExecutionResult(CommandMarkers.SECURITY_VIOLATION + " 找不到此刪除使用者指令的待確認紀錄，請重新操作。"),
                    null);
        }

        if (!userExists(username, actor)) {
            return failedResult(formatExecutionResult("錯誤：使用者 " + username + " 不存在。"), null);
        }

        var deleteExecution = runTrustedRootWithResult(pseudoCommand, actor);
        String deleteContent = extractToolResult(deleteExecution.rawToolResult());
        if (!deleteExecution.success()) {
            return failedResult(deleteExecution.rawToolResult(), deleteExecution.exitCode());
        }

        if (isBenignDeleteWarning(deleteContent)
                || deleteContent.isBlank()
                || "（無輸出）".equals(deleteContent)
                || "(No Output)".equals(deleteContent)) {
            return successResult(formatExecutionResult("使用者 " + username + " 已刪除。"), deleteExecution.exitCode());
        }
        return successResult(
                formatExecutionResult("使用者 " + username + " 已刪除。\n" + deleteContent),
                deleteExecution.exitCode());
    }

    private ManagedActionResult successResult(String rawToolResult, Integer exitCode) {
        return new ManagedActionResult(rawToolResult, true, exitCode);
    }

    private ManagedActionResult failedResult(String rawToolResult, Integer exitCode) {
        return new ManagedActionResult(rawToolResult, false, exitCode);
    }

    /**
     * 建立指定使用者的 .ssh 目錄與 authorized_keys 檔案，並設定正確的權限與 ownership。
     * 若已存在則不影響現有內容（mkdir -p + touch 為冪等操作）。
     */
    private String setupSshDirectory(String username, String actor) {
        return setupSshDirectoryWithResult(username, actor).rawToolResult();
    }

    private CommandExecutionService.ExecutionResult setupSshDirectoryWithResult(String username, String actor) {
        String homeDir = "root".equals(username) ? "/root" : "/home/" + username;
        String sshDir = homeDir + "/.ssh";
        String keysFile = sshDir + "/authorized_keys";
        String cmd = String.format(
            "mkdir -p %s && touch %s && chmod 700 %s && chmod 600 %s && chown -R %s:%s %s",
            sshDir, keysFile, sshDir, keysFile, username, username, sshDir
        );
        return runTrustedRootWithResult(cmd, actor);
    }

    /**
     * Sets a user's password via ProcessBuilder + stdin to avoid shell injection.
     * Pipes "username:password\n" directly to chpasswd's stdin.
     *
     * @return error message on failure, null on success
     */
    protected String setPasswordViaStdin(String username, char[] password) {
        byte[] usernameBytes = null;
        byte[] passwordBytes = null;
        byte[] credentialLine = null;
        byte[] processOutputBytes = null;
        Process process = null;
        ExecutorService outputReaderExecutor = null;
        Future<byte[]> outputFuture = null;
        try {
            if (password == null) {
                return "password is null";
            }
            usernameBytes = username.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            passwordBytes = charArrayToUtf8(password);
            credentialLine = new byte[usernameBytes.length + passwordBytes.length + 2];
            System.arraycopy(usernameBytes, 0, credentialLine, 0, usernameBytes.length);
            credentialLine[usernameBytes.length] = (byte) ':';
            System.arraycopy(passwordBytes, 0, credentialLine, usernameBytes.length + 1, passwordBytes.length);
            credentialLine[credentialLine.length - 1] = (byte) '\n';

            ProcessBuilder pb = new ProcessBuilder("chpasswd");
            pb.redirectErrorStream(true);
            process = pb.start();
            Process capturedProcess = process;
            outputReaderExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "chpasswd-output-reader");
                thread.setDaemon(true);
                return thread;
            });
            outputFuture = outputReaderExecutor.submit(() -> capturedProcess.getInputStream().readAllBytes());
            try (java.io.OutputStream os = process.getOutputStream()) {
                os.write(credentialLine);
                os.flush();
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                destroyForciblyAndReapProcess(process, FORCED_PROCESS_REAP_TIMEOUT_SECONDS);
                return "chpasswd timeout";
            }
            if (outputFuture != null) {
                try {
                    processOutputBytes = outputFuture.get(1, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    outputFuture.cancel(true);
                    processOutputBytes = new byte[0];
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw new RuntimeException(cause);
                }
            } else {
                processOutputBytes = new byte[0];
            }
            String output = new String(processOutputBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0) {
                return output.isEmpty() ? "chpasswd exit code " + process.exitValue() : output;
            }
            return null; // success
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "chpasswd interrupted";
        } catch (Exception e) {
            String message = e.getMessage();
            return (message == null || message.isBlank()) ? e.toString() : message;
        } finally {
            if (process != null && process.isAlive()) {
                destroyForciblyAndReapProcess(process, FORCED_PROCESS_REAP_TIMEOUT_SECONDS);
            }
            if (outputFuture != null && !outputFuture.isDone()) {
                outputFuture.cancel(true);
            }
            if (outputReaderExecutor != null) {
                outputReaderExecutor.shutdownNow();
            }
            if (usernameBytes != null) Arrays.fill(usernameBytes, (byte) 0);
            if (passwordBytes != null) Arrays.fill(passwordBytes, (byte) 0);
            if (credentialLine != null) Arrays.fill(credentialLine, (byte) 0);
            if (processOutputBytes != null) Arrays.fill(processOutputBytes, (byte) 0);
            clearSensitivePassword(password);
        }
    }

    private void destroyForciblyAndReapProcess(Process process, long timeoutSeconds) {
        if (process == null) {
            return;
        }
        process.destroyForcibly();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                // Register async reaping path to avoid lingering zombie state if exit is delayed.
                registerAsyncReaper(process);
                log.warn("[UserMgmt] Process still alive after destroyForcibly(); timeout={}s", timeoutSeconds);
            }
        } catch (InterruptedException interrupted) {
            registerAsyncReaper(process);
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            registerAsyncReaper(process);
            log.warn("[UserMgmt] Failed to reap forcibly-destroyed process: {}", exception.getMessage());
        }
    }

    private void registerAsyncReaper(Process process) {
        if (process == null) {
            return;
        }
        try {
            process.onExit();
        } catch (Exception ignored) {
            // Best-effort cleanup fallback only.
        }
    }

    /**
     * Appends an SSH public key using FileOperationService.writeFileContent.
     * This preserves symlink protections provided by file operations.
     *
     * @return error message on failure, null on success
     */
    private String appendSshKeyViaStdin(String filePath, String publicKey, String actor) {
        Object lock = sshKeyFileLocks.get(filePath, ignored -> new Object());
        synchronized (lock) {
            try {
                Path targetPath = Paths.get(filePath);
                String existingContent = "";
                if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(targetPath)) {
                        return CommandMarkers.SECURITY_VIOLATION + " 拒絕寫入符號連結目標: " + filePath;
                    }
                    try (SeekableByteChannel channel = Files.newByteChannel(
                            targetPath,
                            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
                        ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
                        while (buffer.hasRemaining()) {
                            channel.read(buffer);
                        }
                        existingContent = new String(buffer.array(), StandardCharsets.UTF_8);
                    }
                }

                StringBuilder mergedContent = new StringBuilder(existingContent);
                if (!existingContent.isEmpty() && !existingContent.endsWith("\n")) {
                    mergedContent.append('\n');
                }
                mergedContent.append(publicKey).append('\n');

                String writeResult = fileOperationService.writeFileContent(filePath, mergedContent.toString(), actor);
                String writeMessage = extractToolResult(writeResult);
                String lowerMessage = writeMessage.toLowerCase(Locale.ROOT);
                if (lowerMessage.contains("[security_violation]")
                        || writeMessage.startsWith("錯誤")
                        || writeMessage.contains("失敗")) {
                    return writeMessage.isBlank() ? "writeFileContent failed" : writeMessage;
                }
                return null;
            } catch (Exception e) {
                return e.getMessage();
            }
        }
    }

    private void saveAuditLog(String command, String output, boolean success, String actor) {
        try {
            String currentUser = normalizeValidatedActor(actor);
            if (currentUser == null) currentUser = "system";

            CommandLog cmdLog = new CommandLog();
            cmdLog.setUsername(currentUser);
            cmdLog.setCommand(command);
            cmdLog.setOutput(CommandAuditService.truncateOutput(output));
            cmdLog.setSuccess(success);
            cmdLog.setCommandType(CommandAuditService.classifyCommandType(command));
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "UserManagementService");
        } catch (Exception ex) {
            log.error("[Audit] Failed to save user management log: {}", ex.getMessage());
        }
    }

    private String resolveTargetUser(String username, String actor) {
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return isValidActor(actor) ? actor.trim() : null;
    }

    private String rollbackCreatedUser(String username, String actor) {
        var deleteExecution = runTrustedRootWithResult("userdel -r " + username, actor);
        String deleteContent = extractToolResult(deleteExecution.rawToolResult());
        if (!deleteExecution.success() && !isBenignDeleteWarning(deleteContent)) {
            String rollbackError = deleteContent;
            if (rollbackError.isBlank() || "（無輸出）".equals(rollbackError) || "(No Output)".equals(rollbackError)) {
                rollbackError = (deleteExecution.exitCode() == null)
                        ? "rollback 失敗（無輸出）"
                        : "rollback 失敗（exit code: " + deleteExecution.exitCode() + "，無輸出）";
            } else if (deleteExecution.exitCode() != null) {
                rollbackError = "rollback 失敗（exit code: " + deleteExecution.exitCode() + "）： " + rollbackError;
            } else {
                rollbackError = "rollback 失敗： " + rollbackError;
            }
            saveAuditLog("userdel -r " + username + " (rollback)", rollbackError, false, actor);
            return rollbackError;
        }
        saveAuditLog("userdel -r " + username + " (rollback)", "rollback success", true, actor);
        return null;
    }

    private CommandExecutionService.ExecutionResult runTrustedRootWithResult(String command, String actor) {
        return commandExecutionService.executeWithResult(
                command,
                CommandExecutionService.ExecutionOptions.builder().trustedRoot().user(actor).build());
    }

    private boolean isExecutionFailed(String rawResult) {
        String content = extractToolResult(rawResult).toLowerCase();
        return content.contains("[security_violation]")
            || content.contains("command execution failed")
            || content.contains("sudo authentication failed")
            || content.contains("permission denied")
            || content.contains("exception occurred")
            || content.contains("invalid");
    }

    private boolean isBenignDeleteWarning(String content) {
        if (content == null) return false;
        String lower = content.toLowerCase();
        return lower.contains("mail spool") && lower.contains("not found");
    }

    private boolean userExists(String username, String actor) {
        if (username == null || username.isBlank()) return false;
        if ("root".equals(username)) return true;
        if (!isValidLinuxUsername(username)) return false;

        // Use getent when possible (more correct for NSS), fallback to /etc/passwd.
        String cmd = "if command -v getent >/dev/null 2>&1; then " +
                "getent passwd " + username + " >/dev/null 2>&1; " +
                "else " +
                "grep -qE '^" + username + ":' /etc/passwd; " +
                "fi; " +
                "if [ $? -eq 0 ]; then echo EXISTS; else echo MISSING; fi";
        String raw = commandExecutionService.execute(
                cmd,
                CommandExecutionService.ExecutionOptions.builder().trustedRoot().user(actor).build());
        String out = extractToolResult(raw).trim();
        return out.contains("EXISTS");
    }

    private String normalizeValidatedActor(String actor) {
        if (!isValidActor(actor)) return null;
        return actor.trim();
    }

    private boolean isValidActor(String actor) {
        return actor != null && !actor.isBlank() && !"anonymous".equalsIgnoreCase(actor.trim());
    }

    private String buildAddPseudoCommand(String username) {
        return "useradd -m -s /bin/bash " + username;
    }

    private String buildDeletePseudoCommand(String username) {
        return "userdel -r " + username;
    }


    private char[] clonePassword(char[] password) {
        return password == null ? null : password.clone();
    }

    private boolean hasNonBlankPassword(char[] password) {
        if (password == null) {
            return false;
        }
        for (char ch : password) {
            if (!Character.isWhitespace(ch)) {
                return true;
            }
        }
        return false;
    }

    private byte[] charArrayToUtf8(char[] value) {
        java.nio.ByteBuffer encoded = java.nio.charset.StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return bytes;
    }

    private void clearSensitivePendingAddRequest(PendingAddRequest request) {
        if (request == null) {
            return;
        }
        request.clearSensitiveData();
    }

    private void clearSensitivePassword(char[] password) {
        if (password != null) {
            Arrays.fill(password, '\0');
        }
    }

    private record PendingAddRequest(char[] password) implements SensitivePendingPayload {
        @Override
        public void clearSensitiveData() {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }
}
