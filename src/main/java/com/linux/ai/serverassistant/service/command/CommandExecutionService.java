package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.security.CommandValidator;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UsernameUtils;
import com.linux.ai.serverassistant.util.UserContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static com.linux.ai.serverassistant.util.ToolResultUtils.formatExecutionResult;

/**
 * Command Execution Service
 *
 * Handles secure Linux command execution with multiple layers of protection:
 * - Command validation and sanitization
 * - Blacklist filtering for dangerous commands
 * - Command optimization for common monitoring tools
 * - Sudo password injection from encrypted credential store (via {@link SudoCredentialInjector})
 * - Output size limiting to prevent memory issues
 * - Comprehensive audit logging
 * - High-risk command confirmation mechanism (state via {@link CommandConfirmationService})
 */
@Service
public class CommandExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutionService.class);

    // ========== Constants ==========

    @Value("${app.command.output.max-chars-ai:2000}")
    private int maxOutputCharsAi;

    @Value("${app.command.output.max-chars-deterministic:8000}")
    private int maxOutputCharsDeterministic;

    /**
     * Command timeouts — configurable via app.command.timeout.* properties.
     *
     * Note: timeout enforcement must not rely on blocking stream reads.
     */
    @Value("${app.command.timeout.default:10}")
    private long defaultTimeoutSeconds;

    @Value("${app.command.timeout.du:30}")
    private long duTimeoutSeconds;

    @Value("${app.command.timeout.find:60}")
    private long findTimeoutSeconds;

    @Value("${app.command.timeout.archive:300}")
    private long archiveTimeoutSeconds;          // zip, unzip, 7z

    @Value("${app.command.timeout.package-install:300}")
    private long packageInstallTimeoutSeconds;   // npm, yarn, pnpm, pip, pip3, git

    @Value("${app.command.timeout.apt:600}")
    private long aptTimeoutSeconds;              // apt, apt-get, docker, mysqldump, pg_dump

    @Value("${app.command.timeout.long-running:1800}")
    private long longRunningTimeoutSeconds;      // rsync, tar, cp, wget, curl, ffmpeg

    private static final long OUTPUT_CAPTURE_DRAIN_TIMEOUT_SECONDS = 1;
    private static final long SUDO_AUTH_TIMEOUT_SECONDS = 5;
    private static final int SUDO_AUTH_OUTPUT_MAX_CHARS = 512;
    private static final long EXIT_CODE_STABILIZATION_TIMEOUT_SECONDS = 1;
    private static final long PROCESS_DESTROY_GRACE_PERIOD_SECONDS = 2;
    private static final long PROCESS_DESTROY_FORCE_WAIT_SECONDS = 1;

    public enum ExecutionState {
        COMPLETED,
        FAILED,
        PENDING_CONFIRMATION
    }

    public record ExecutionResult(String rawToolResult, boolean success, Integer exitCode, ExecutionState state) {
        public ExecutionResult {
            Objects.requireNonNull(state, "state");
            if (success && state != ExecutionState.COMPLETED) {
                throw new IllegalArgumentException("success=true requires state=COMPLETED");
            }
            if (!success && state == ExecutionState.COMPLETED) {
                throw new IllegalArgumentException("state=COMPLETED requires success=true");
            }
        }

        public ExecutionResult(String rawToolResult, boolean success, Integer exitCode) {
            this(rawToolResult, success, exitCode, success ? ExecutionState.COMPLETED : ExecutionState.FAILED);
        }

        public boolean isPendingConfirmation() {
            return state == ExecutionState.PENDING_CONFIRMATION;
        }
    }
    private enum ExecutionType {
        USER,
        TRUSTED_ROOT,
        CONFIRMED_USER
    }
    private record UserCommandExecutionMode(boolean applyOptimization, boolean auditEnabled, int maxOutputChars) {}
    public static final class ExecutionOptions {
        private final ExecutionType executionType;
        private final boolean confirm;
        private final boolean applyOptimization;
        private final boolean auditEnabled;
        private final boolean skipPendingCheck;
        private final String username;
        private final Long timeoutSecondsOverride;
        private final Integer maxOutputCharsOverride;

        private ExecutionOptions(Builder b) {
            this.executionType = b.executionType;
            this.confirm = b.confirm;
            this.applyOptimization = b.applyOptimization;
            this.auditEnabled = b.auditEnabled;
            this.skipPendingCheck = b.skipPendingCheck;
            this.username = b.username;
            this.timeoutSecondsOverride = b.timeoutSecondsOverride;
            this.maxOutputCharsOverride = b.maxOutputCharsOverride;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static ExecutionOptions of() {
            return builder().build();
        }

        public String username() { return username; }
        public boolean confirm() { return confirm; }
        public boolean applyOptimization() { return applyOptimization; }
        public boolean auditEnabled() { return auditEnabled; }
        public boolean skipPendingCheck() { return skipPendingCheck; }
        public Long timeoutSecondsOverride() { return timeoutSecondsOverride; }
        public Integer maxOutputCharsOverride() { return maxOutputCharsOverride; }
        private ExecutionType executionType() { return executionType; }

        public static final class Builder {
            private ExecutionType executionType = ExecutionType.USER;
            private boolean confirm = false;
            private boolean applyOptimization = true;
            private boolean auditEnabled = true;
            private boolean skipPendingCheck = false;
            private String username = null;
            private Long timeoutSecondsOverride = null;
            private Integer maxOutputCharsOverride = null;

            private Builder() {}

            public Builder user(String username) {
                this.username = username;
                return this;
            }

            public Builder confirm(boolean confirm) {
                this.confirm = confirm;
                return this;
            }

            public Builder noOptimize() {
                return optimize(false);
            }

            public Builder optimize(boolean applyOptimization) {
                this.applyOptimization = applyOptimization;
                return this;
            }

            public Builder noAudit() {
                return audit(false);
            }

            public Builder audit(boolean auditEnabled) {
                this.auditEnabled = auditEnabled;
                return this;
            }

            public Builder skipPendingCheck() {
                return skipPendingCheck(true);
            }

            public Builder skipPendingCheck(boolean skipPendingCheck) {
                this.skipPendingCheck = skipPendingCheck;
                return this;
            }

            public Builder trustedRoot() {
                this.executionType = ExecutionType.TRUSTED_ROOT;
                return this;
            }

            public Builder confirmed() {
                this.executionType = ExecutionType.CONFIRMED_USER;
                return this;
            }

            public Builder timeoutSeconds(long timeoutSeconds) {
                return timeoutSeconds(Long.valueOf(timeoutSeconds));
            }

            public Builder timeoutSeconds(Long timeoutSecondsOverride) {
                this.timeoutSecondsOverride = timeoutSecondsOverride;
                return this;
            }

            public Builder maxOutputChars(int maxOutputChars) {
                this.maxOutputCharsOverride = maxOutputChars;
                return this;
            }

            public ExecutionOptions build() {
                return new ExecutionOptions(this);
            }
        }
    }
    private record CaptureTask(ProcessOutputCapture capture, Future<?> captureFuture) {}
    private record OutputCaptureExecutorSettings(
            ExecutorService outputCaptureExecutor,
            boolean manageOutputCaptureExecutorLifecycle) {}

    // ========== Default process factory ==========

    static final class DefaultProcessFactory implements ProcessFactory {
        @Override
        public Process startCommand(boolean forceRoot, String user, String command) throws IOException {
            ProcessBuilder pb = forceRoot
                    ? new ProcessBuilder("/bin/bash", "-c", command)
                    : new ProcessBuilder("sudo", "-n", "-u", user, "/bin/bash", "-c", command);
            pb.redirectErrorStream(true);
            return pb.start();
        }

        @Override
        public Process startSudoAuth(String user) throws IOException {
            return createSudoAuthProcessBuilder(user).start();
        }

        @Override
        public ProcessBuilder createSudoAuthProcessBuilder(String user) {
            ProcessBuilder pb = new ProcessBuilder("sudo", "-k", "-S", "-p", "", "-u", user, "-v");
            pb.environment().put("LANG", "C");
            pb.environment().put("LC_ALL", "C");
            pb.redirectErrorStream(true);
            return pb;
        }
    }

    // ========== Dependencies ==========

    private final CommandValidator commandValidator;
    private final CommandOptimizer commandOptimizer;
    private final CommandAuditService commandAuditService;
    private final UserContext userContext;
    private final SudoCredentialInjector sudoCredentialInjector;
    private final CommandConfirmationService commandConfirmationService;
    private final ProcessFactory processFactory;

    private final ExecutorService outputCaptureExecutor;
    private final boolean manageOutputCaptureExecutorLifecycle;

    // ========== Constructor ==========

    @Autowired
    public CommandExecutionService(
            CommandValidator commandValidator,
            CommandLogRepository commandLogRepository,
            UserContext userContext,
            SudoCredentialInjector sudoCredentialInjector,
            CommandConfirmationService commandConfirmationService,
            @Qualifier("commandOutputCaptureExecutor") ExecutorService outputCaptureExecutor) {
        this(
                builder(commandValidator, commandLogRepository, userContext, sudoCredentialInjector,
                        commandConfirmationService)
                        .outputCaptureExecutor(outputCaptureExecutor)
                        .manageOutputCaptureExecutorLifecycle(false));
    }

    public CommandExecutionService(
            CommandValidator commandValidator,
            CommandLogRepository commandLogRepository,
            UserContext userContext,
            SudoCredentialInjector sudoCredentialInjector,
            CommandConfirmationService commandConfirmationService) {
        this(builder(commandValidator, commandLogRepository, userContext, sudoCredentialInjector,
                commandConfirmationService));
    }

    private CommandExecutionService(Builder builder) {
        OutputCaptureExecutorSettings outputCaptureExecutorSettings = builder.resolveOutputCaptureExecutorSettings();
        this.commandValidator = builder.commandValidator;
        this.commandOptimizer = new CommandOptimizer();
        this.commandAuditService = new CommandAuditService(builder.commandLogRepository);
        this.userContext = builder.userContext;
        this.sudoCredentialInjector = builder.sudoCredentialInjector;
        this.commandConfirmationService = builder.commandConfirmationService;
        this.processFactory = (builder.processFactory != null) ? builder.processFactory : new DefaultProcessFactory();
        this.outputCaptureExecutor = outputCaptureExecutorSettings.outputCaptureExecutor();
        this.manageOutputCaptureExecutorLifecycle =
                outputCaptureExecutorSettings.manageOutputCaptureExecutorLifecycle();
    }

    public static Builder builder(
            CommandValidator commandValidator,
            CommandLogRepository commandLogRepository,
            UserContext userContext,
            SudoCredentialInjector sudoCredentialInjector,
            CommandConfirmationService commandConfirmationService) {
        return new Builder(commandValidator, commandLogRepository, userContext,
                sudoCredentialInjector, commandConfirmationService);
    }

    public static final class Builder {
        private final CommandValidator commandValidator;
        private final CommandLogRepository commandLogRepository;
        private final UserContext userContext;
        private final SudoCredentialInjector sudoCredentialInjector;
        private final CommandConfirmationService commandConfirmationService;

        private ExecutorService outputCaptureExecutor;
        private Boolean manageOutputCaptureExecutorLifecycle;
        private ProcessFactory processFactory;

        private Builder(
                CommandValidator commandValidator,
                CommandLogRepository commandLogRepository,
                UserContext userContext,
                SudoCredentialInjector sudoCredentialInjector,
                CommandConfirmationService commandConfirmationService) {
            this.commandValidator = Objects.requireNonNull(commandValidator, "commandValidator");
            this.commandLogRepository = Objects.requireNonNull(commandLogRepository, "commandLogRepository");
            this.userContext = Objects.requireNonNull(userContext, "userContext");
            this.sudoCredentialInjector = Objects.requireNonNull(sudoCredentialInjector, "sudoCredentialInjector");
            this.commandConfirmationService =
                    Objects.requireNonNull(commandConfirmationService, "commandConfirmationService");
        }

        public Builder outputCaptureExecutor(ExecutorService outputCaptureExecutor) {
            this.outputCaptureExecutor = Objects.requireNonNull(outputCaptureExecutor, "outputCaptureExecutor");
            return this;
        }

        public Builder manageOutputCaptureExecutorLifecycle(boolean manageOutputCaptureExecutorLifecycle) {
            this.manageOutputCaptureExecutorLifecycle = manageOutputCaptureExecutorLifecycle;
            return this;
        }

        public Builder processFactory(ProcessFactory processFactory) {
            this.processFactory = processFactory;
            return this;
        }

        public CommandExecutionService build() {
            return new CommandExecutionService(this);
        }

        private OutputCaptureExecutorSettings resolveOutputCaptureExecutorSettings() {
            if (outputCaptureExecutor == null) {
                return new OutputCaptureExecutorSettings(createDefaultOutputCaptureExecutor(), true);
            }
            boolean manageLifecycle =
                    (manageOutputCaptureExecutorLifecycle != null) && manageOutputCaptureExecutorLifecycle;
            return new OutputCaptureExecutorSettings(outputCaptureExecutor, manageLifecycle);
        }
    }

    // ========== Public API ==========

    /**
     * Executes command using a single options object instead of multiple overloads.
     */
    public String execute(String command, ExecutionOptions options) {
        return executeWithResult(command, options).rawToolResult();
    }

    /**
     * Executes command and returns structured execution metadata.
     */
    public ExecutionResult executeWithResult(String command, ExecutionOptions options) {
        ExecutionOptions effectiveOptions = (options != null) ? options : ExecutionOptions.of();
        return switch (effectiveOptions.executionType()) {
            case USER -> executeUserCommandWithResult(
                    command,
                    effectiveOptions.confirm(),
                    effectiveOptions.username(),
                    resolveUserExecutionMode(effectiveOptions),
                    effectiveOptions.timeoutSecondsOverride());
            case TRUSTED_ROOT -> executeTrustedRootCommandWithResultInternal(
                    command,
                    effectiveOptions.timeoutSecondsOverride(),
                    effectiveOptions.username(),
                    resolveMaxOutputChars(effectiveOptions),
                    effectiveOptions.auditEnabled());
            case CONFIRMED_USER -> executeConfirmedLinuxCommandWithResultInternal(
                    command,
                    effectiveOptions.username(),
                    !effectiveOptions.skipPendingCheck(),
                    effectiveOptions.applyOptimization(),
                    effectiveOptions.auditEnabled(),
                    resolveMaxOutputChars(effectiveOptions),
                    effectiveOptions.timeoutSecondsOverride());
        };
    }

    /**
     * Transactional entry point for user-confirmed high-risk commands.
     */
    @Transactional
    public ExecutionResult executeConfirmedCommandWithResult(String command, String username) {
        return executeConfirmedLinuxCommandWithResultInternal(
                command,
                username,
                true,
                true,
                true,
                maxOutputCharsAi,
                null);
    }

    private UserCommandExecutionMode resolveUserExecutionMode(ExecutionOptions options) {
        return new UserCommandExecutionMode(
                options.applyOptimization(),
                options.auditEnabled(),
                resolveMaxOutputChars(options));
    }

    private int resolveMaxOutputChars(ExecutionOptions options) {
        Integer override = options.maxOutputCharsOverride();
        if (override != null && override > 0) {
            return override;
        }
        return switch (options.executionType()) {
            case USER -> options.applyOptimization() ? maxOutputCharsAi : maxOutputCharsDeterministic;
            case TRUSTED_ROOT, CONFIRMED_USER -> maxOutputCharsAi;
        };
    }

    private ExecutionResult executeUserCommandWithResult(
            String command,
            boolean confirm,
            String username,
            UserCommandExecutionMode mode,
            Long timeoutSecondsOverride) {
        if (command == null || command.isBlank()) {
            return failedResult(CommandMarkers.SECURITY_VIOLATION + " 命令不能為空。");
        }
        String trimmedCmd = command.trim();
        log.debug("Tool Request: cmd='{}', confirm={}, optimize={}, audit={}",
                trimmedCmd, confirm, mode.applyOptimization(), mode.auditEnabled());

        CommandValidator.ValidationResult validationResult = commandValidator.validate(trimmedCmd);

        // 1. If validation fails, reject immediately
        if (validationResult instanceof CommandValidator.Invalid invalid) {
            log.warn("Command validation failed: {}", invalid.errorMessage());
            return failedResult(CommandMarkers.SECURITY_VIOLATION + " " + invalid.errorMessage());
        }

        // 2. If it's a high-risk command and requires confirmation
        if (validationResult instanceof CommandValidator.RequiresConfirmation) {
            boolean explicitUserProvided = username != null && !username.isBlank();
            String user = explicitUserProvided
                    ? UsernameUtils.normalizeUsernameOrAnonymous(username)
                    : UsernameUtils.normalizeUsernameOrAnonymous(getCurrentUsername());
            if (explicitUserProvided && "anonymous".equals(user)) {
                return failedResult(CommandMarkers.SECURITY_VIOLATION + " 無法識別目前使用者，請重新登入後再試。");
            }

            if (!confirm) {
                commandConfirmationService.storePendingCommand(user, trimmedCmd);
                log.debug("High-risk command blocked. Requesting confirmation.");
                return new ExecutionResult(
                        getConfirmationPrompt(trimmedCmd),
                        false,
                        null,
                        ExecutionState.PENDING_CONFIRMATION);
            }

            // Verify the validity of the confirmation request
            if (!commandConfirmationService.consumePendingConfirmation(trimmedCmd, user)) {
                // Auto-correct: skipping confirm=false. Instead of rejecting, treat as initial request.
                log.debug("Auto-correcting confirm=true → storing pending (no valid prior request found)");
                commandConfirmationService.storePendingCommand(user, trimmedCmd);
                return new ExecutionResult(
                        getConfirmationPrompt(trimmedCmd),
                        false,
                        null,
                        ExecutionState.PENDING_CONFIRMATION);
            }
        }

        // 3. Optional command optimization for AI tool path
        if (mode.applyOptimization()) {
            trimmedCmd = commandOptimizer.optimizeForAi(trimmedCmd);
        }

        return runCommandWithResult(
                trimmedCmd,
                false,
                username,
                mode.maxOutputChars(),
                mode.auditEnabled(),
                normalizeTimeoutOverride(timeoutSecondsOverride));
    }

    private ExecutionResult executeTrustedRootCommandWithResultInternal(
            String command,
            Long timeoutSecondsOverride,
            String actorUsername,
            int maxOutputChars,
            boolean auditEnabled) {
        if (command == null || command.trim().isEmpty()) {
            return failedResult("錯誤：命令不能為空");
        }
        String trimmedCmd = command.trim();
        log.debug("executeTrustedRootCommand: cmd='{}'", trimmedCmd);
        return runCommandWithResult(
                trimmedCmd,
                true,
                actorUsername,
                maxOutputChars,
                auditEnabled,
                normalizeTimeoutOverride(timeoutSecondsOverride));
    }

    private ExecutionResult executeConfirmedLinuxCommandWithResultInternal(
            String command,
            String username,
            boolean consumePending,
            boolean applyOptimization,
            boolean auditEnabled,
            int maxOutputChars,
            Long timeoutSecondsOverride) {
        if (command == null || command.isBlank()) {
            return failedResult(CommandMarkers.SECURITY_VIOLATION + " 命令不能為空。");
        }
        final String originalCmd = command.trim();
        final String user = (username != null) ? username : "anonymous";
        log.debug("executeConfirmedLinuxCommand: cmd='{}', user='{}'", originalCmd, user);

        if (consumePending && !commandConfirmationService.consumePendingConfirmation(originalCmd, user)) {
            log.warn("No valid pending confirmation for user='{}', cmd='{}'", user, originalCmd);
            return failedResult(CommandMarkers.SECURITY_VIOLATION + " 找不到此指令的待確認紀錄，請重新操作。");
        }

        CommandValidator.ValidationResult validationResult = commandValidator.validate(originalCmd);
        if (validationResult instanceof CommandValidator.Invalid invalid) {
            return failedResult(CommandMarkers.SECURITY_VIOLATION + " " + invalid.errorMessage());
        }

        String execCmd = applyOptimization
                ? commandOptimizer.optimizeForAi(originalCmd)
                : originalCmd;
        return runCommandWithResult(
                execCmd,
                false,
                username,
                maxOutputChars,
                auditEnabled,
                normalizeTimeoutOverride(timeoutSecondsOverride));
    }

    // ========== Internal Methods ==========

    /**
     * Execution seam for unit tests: allows asserting propagated options without
     * relying on platform-specific process behavior.
     */
    protected ExecutionResult runCommandWithResult(
            String command,
            boolean forceRootExecution,
            String actorUsername,
            int maxOutputChars,
            boolean auditEnabled,
            Long timeoutSecondsOverride) {
        return executeCommandInternalWithResult(
                command,
                forceRootExecution,
                actorUsername,
                maxOutputChars,
                auditEnabled,
                timeoutSecondsOverride);
    }

    private ExecutionResult executeCommandInternalWithResult(
            String command,
            boolean forceRootExecution,
            String actorUsername,
            int maxOutputChars,
            boolean auditEnabled,
            Long timeoutSecondsOverride) {
        Process process = null;
        ProcessOutputCapture capture = null;
        boolean success = true;
        Integer exitCode = null;
        String auditUsername = actorUsername;
        char[] sessionPassChars = null;

        try {
            SudoCredentialInjector.ResolvedSession session =
                    sudoCredentialInjector.resolveSession(forceRootExecution, actorUsername);

            if (session.failed()) {
                if (auditEnabled) {
                    commandAuditService.saveCommandLog(
                            command, session.failureReason(), false, null,
                            resolveAuditUsername(session.auditUsername()));
                }
                return failedResult(session.failureReason());
            }

            auditUsername = session.auditUsername();
            sessionPassChars = session.passChars();

            if (!forceRootExecution) {
                authenticateSudoSession(session.sessionUser(), sessionPassChars);
                sessionPassChars = null; // ownership transferred to authenticateSudoSession
            }

            process = startCommandProcess(forceRootExecution, session.sessionUser(), command);
            CaptureTask captureTask = startOutputCapture(process, maxOutputChars);
            capture = captureTask.capture();

            boolean finished = waitForProcessCompletion(process, command, timeoutSecondsOverride, capture);
            if (!finished) {
                success = false;
            }
            if (drainCaptureOutput(process, captureTask.captureFuture(), capture)) {
                success = false;
            }
            if (finished) {
                exitCode = resolveProcessExitCode(process);
                if (exitCode == null) {
                    capture.appendNotice("\n[系統提示] 命令狀態確認失敗：無法取得結束碼。");
                    success = false;
                } else {
                    success = updateExecutionStatusFromExitCode(exitCode, capture) && success;
                }
            }
        } catch (IOException | TimeoutException | InterruptedException | RuntimeException e) {
            destroyProcessQuietly(process);
            capture = handleExecutionException(capture, e, maxOutputChars);
            success = false;
        } finally {
            SudoCredentialInjector.clearSensitiveChars(sessionPassChars);
        }

        String result = normalizeExecutionOutput(command, capture);

        if (auditEnabled) {
            commandAuditService.saveCommandLog(
                    command, result, success, exitCode, resolveAuditUsername(auditUsername));
        }

        return new ExecutionResult(formatExecutionResult(result), success, exitCode);
    }

    private Process startCommandProcess(boolean forceRootExecution, String sessionUser, String command)
            throws IOException {
        return processFactory.startCommand(forceRootExecution, sessionUser, command);
    }

    private Process startSudoAuthenticationProcess(String sessionUser) throws IOException {
        return processFactory.startSudoAuth(sessionUser);
    }

    private CaptureTask startOutputCapture(Process process, int maxOutputChars) throws InterruptedException {
        ProcessOutputCapture capture = new ProcessOutputCapture(process, maxOutputChars);
        try {
            Future<?> captureFuture = outputCaptureExecutor.submit(capture);
            return new CaptureTask(capture, captureFuture);
        } catch (RejectedExecutionException rejected) {
            destroyProcess(process);
            throw new IllegalStateException("系統忙碌：命令輸出擷取執行緒池已滿，請稍後再試。", rejected);
        }
    }

    private boolean waitForProcessCompletion(
            Process process, String command, Long timeoutSecondsOverride, ProcessOutputCapture capture)
            throws InterruptedException {
        long timeoutSeconds = (timeoutSecondsOverride != null && timeoutSecondsOverride > 0)
                ? timeoutSecondsOverride
                : computeTimeoutSeconds(command);
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            capture.markTimedOut(timeoutSeconds);
            destroyProcess(process);
        }
        return finished;
    }

    private Integer resolveProcessExitCode(Process process) throws InterruptedException {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException raceWindow) {
            boolean stabilized = process.waitFor(EXIT_CODE_STABILIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!stabilized) {
                return null;
            }
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException ignored) {
                return null;
            }
        }
    }

    private boolean drainCaptureOutput(Process process, Future<?> captureFuture, ProcessOutputCapture capture)
            throws InterruptedException {
        if (captureFuture == null) {
            return false;
        }
        try {
            captureFuture.get(OUTPUT_CAPTURE_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return false;
        } catch (TimeoutException timeout) {
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
                // Ignore close failures and still try to interrupt capture worker.
            }
            if (captureFuture.cancel(true)) {
                capture.appendNotice("\n[系統提示] 命令輸出擷取逾時，已提前結束擷取；輸出可能不完整。");
            }
            return false;
        } catch (ExecutionException e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            String message = (cause.getMessage() == null || cause.getMessage().isBlank())
                    ? cause.getClass().getSimpleName()
                    : cause.getMessage();
            capture.appendNotice("\n[系統提示] 命令輸出擷取失敗：" + message);
            return true;
        }
    }

    private boolean updateExecutionStatusFromExitCode(int exitCode, ProcessOutputCapture capture) {
        if (exitCode == 0) {
            return true;
        }
        boolean truncatedBySystem = capture.wasTruncated()
                && (exitCode == 141 || exitCode == 143 || exitCode == 137 || exitCode == 130);
        if (!truncatedBySystem) {
            capture.appendNotice("\n[系統提示] 命令執行失敗（Exit Code: " + exitCode + "）");
            return false;
        }
        return true;
    }

    private ProcessOutputCapture handleExecutionException(
            ProcessOutputCapture capture, Exception exception, int maxOutputChars) {
        ProcessOutputCapture effectiveCapture =
                (capture != null) ? capture : new ProcessOutputCapture(null, maxOutputChars);
        if (exception instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        String message = (exception.getMessage() == null || exception.getMessage().isBlank())
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
        effectiveCapture.appendNotice((effectiveCapture.getOutput().isEmpty() ? "" : "\n")
                + "命令執行時發生例外：" + message);
        return effectiveCapture;
    }

    private String normalizeExecutionOutput(String command, ProcessOutputCapture capture) {
        String result = (capture != null && !capture.getOutput().isEmpty()) ? capture.getOutput() : "（無輸出）";
        return normalizeQueryCsvForToolResult(command, result);
    }

    private void destroyProcess(Process process) throws InterruptedException {
        process.destroy();
        if (!process.waitFor(PROCESS_DESTROY_GRACE_PERIOD_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(PROCESS_DESTROY_FORCE_WAIT_SECONDS, TimeUnit.SECONDS);
        }
        closeProcessStreamsQuietly(process);
    }

    private void closeProcessStreamsQuietly(Process process) {
        if (process == null) {
            return;
        }
        closeQuietly(process.getInputStream());
        closeQuietly(process.getErrorStream());
        closeQuietly(process.getOutputStream());
    }

    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Ignore close failures during best-effort cleanup.
        }
    }

    private void authenticateSudoSession(String sessionUser, char[] sessionPassChars)
            throws IOException, TimeoutException, InterruptedException {
        if (sessionUser == null || sessionUser.isBlank()) {
            throw new IllegalStateException("Sudo 驗證失敗：無效的執行使用者。");
        }
        Process authProcess = startSudoAuthenticationProcess(sessionUser);
        try {
            SudoCredentialInjector.InjectionResult injectionResult =
                    sudoCredentialInjector.injectPassword(false, sessionPassChars, authProcess);
            if (!injectionResult.wasInjected()) {
                throw new IllegalStateException("Sudo 驗證失敗：密碼注入未完成。");
            }
            boolean finished = authProcess.waitFor(SUDO_AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                destroyProcess(authProcess);
                throw new TimeoutException("Sudo 驗證逾時，請稍後再試。");
            }
            Integer exitCode = resolveProcessExitCode(authProcess);
            if (exitCode == null) {
                throw new IllegalStateException("Sudo 驗證失敗：無法取得結束碼。");
            }
            String authOutput = readBoundedProcessOutput(authProcess, SUDO_AUTH_OUTPUT_MAX_CHARS);
            if (exitCode != 0) {
                throw new IllegalStateException(buildSudoAuthFailureMessage(exitCode, authOutput));
            }
        } catch (IOException | TimeoutException | InterruptedException | RuntimeException e) {
            destroyProcessQuietly(authProcess);
            throw e;
        }
    }

    private String readBoundedProcessOutput(Process process, int maxChars) {
        if (process == null || maxChars <= 0) {
            return "";
        }
        try {
            byte[] bytes = process.getInputStream().readAllBytes();
            if (bytes.length == 0) {
                return "";
            }
            String output = new String(bytes, StandardCharsets.UTF_8).trim();
            if (output.length() <= maxChars) {
                return output;
            }
            return output.substring(0, maxChars) + "...";
        } catch (IOException ignored) {
            return "";
        }
    }

    private String buildSudoAuthFailureMessage(int exitCode, String authOutput) {
        String output = (authOutput == null) ? "" : authOutput.trim();
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("sorry, try again")
                || lower.contains("incorrect password")
                || lower.contains("authentication failure")) {
            return "Sudo 驗證失敗：密碼錯誤或憑證已過期，請重新登入後再試。";
        }
        if (lower.contains("is not in the sudoers")
                || lower.contains("not allowed to run sudo")) {
            return "Sudo 驗證失敗：使用者沒有 sudo 權限。";
        }
        if (!output.isBlank()) {
            return "Sudo 驗證失敗（Exit Code: " + exitCode + "）：" + output;
        }
        return "Sudo 驗證失敗（Exit Code: " + exitCode + "）。";
    }

    private void destroyProcessQuietly(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            destroyProcess(process);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            process.destroyForcibly();
        }
    }

    private ExecutionResult failedResult(String content) {
        return new ExecutionResult(formatExecutionResult(content), false, null);
    }

    @PreDestroy
    void shutdownOwnedOutputCaptureExecutor() {
        if (manageOutputCaptureExecutorLifecycle) {
            outputCaptureExecutor.shutdownNow();
        }
    }

    private long computeTimeoutSeconds(String command) {
        String primaryCommand = resolvePrimaryCommandName(command);
        return switch (primaryCommand) {
            case "rsync", "tar", "cp", "wget", "curl", "ffmpeg" -> longRunningTimeoutSeconds;
            case "apt", "apt-get", "docker", "mysqldump", "pg_dump" -> aptTimeoutSeconds;
            case "npm", "yarn", "pnpm", "pip", "pip3", "git" -> packageInstallTimeoutSeconds;
            case "zip", "unzip", "7z" -> archiveTimeoutSeconds;
            case "find" -> findTimeoutSeconds;
            case "du" -> duTimeoutSeconds;
            default -> defaultTimeoutSeconds;
        };
    }

    private String resolvePrimaryCommandName(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        List<String> tokens = tokenizeCommandPrefix(command);
        int index = 0;
        int safety = 0;
        while (index < tokens.size() && safety++ < 32) {
            String token = sanitizeToken(tokens.get(index));
            if (token.isEmpty()) {
                index++;
                continue;
            }
            if (isEnvironmentAssignment(token)) {
                index++;
                continue;
            }
            String commandName = normalizeCommandName(token);
            if ("sudo".equals(commandName)) {
                index = skipSudoWrapperTokens(tokens, index + 1);
                continue;
            }
            if ("env".equals(commandName)) {
                index = skipEnvWrapperTokens(tokens, index + 1);
                continue;
            }
            return commandName;
        }
        return "";
    }

    private List<String> tokenizeCommandPrefix(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuotes = false;
        boolean inDoubleQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                current.append(c);
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDoubleQuotes) {
                inSingleQuotes = !inSingleQuotes;
                current.append(c);
                continue;
            }
            if (c == '"' && !inSingleQuotes) {
                inDoubleQuotes = !inDoubleQuotes;
                current.append(c);
                continue;
            }
            if (!inSingleQuotes && !inDoubleQuotes && isShellDelimiter(c)) {
                addToken(tokens, current);
                break;
            }
            if (Character.isWhitespace(c) && !inSingleQuotes && !inDoubleQuotes) {
                addToken(tokens, current);
                continue;
            }
            current.append(c);
        }
        addToken(tokens, current);
        return tokens;
    }

    private boolean isShellDelimiter(char c) {
        return c == '|' || c == ';' || c == '&' || c == '<' || c == '>' || c == '(' || c == ')';
    }

    private void addToken(List<String> tokens, StringBuilder current) {
        if (!current.isEmpty()) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    private int skipSudoWrapperTokens(List<String> tokens, int index) {
        while (index < tokens.size()) {
            String token = sanitizeToken(tokens.get(index));
            if (token.isEmpty()) {
                index++;
                continue;
            }
            String lowerToken = token.toLowerCase(Locale.ROOT);
            if ("--".equals(lowerToken)) {
                return index + 1;
            }
            if (!lowerToken.startsWith("-") || "-".equals(lowerToken)) {
                return index;
            }
            if (sudoOptionConsumesNextToken(lowerToken)) {
                index += 2;
                continue;
            }
            index++;
        }
        return index;
    }

    private boolean sudoOptionConsumesNextToken(String option) {
        if (option.startsWith("--")) {
            if (option.contains("=")) {
                return false;
            }
            return "--user".equals(option)
                    || "--group".equals(option)
                    || "--host".equals(option)
                    || "--prompt".equals(option)
                    || "--role".equals(option)
                    || "--type".equals(option)
                    || "--chdir".equals(option)
                    || "--close-from".equals(option);
        }
        if (option.charAt(0) != '-' || option.length() < 2) {
            return false;
        }
        return shortOptionClusterConsumesNextToken(option, "ughprtCD");
    }

    private int skipEnvWrapperTokens(List<String> tokens, int index) {
        while (index < tokens.size()) {
            String token = sanitizeToken(tokens.get(index));
            if (token.isEmpty()) {
                index++;
                continue;
            }
            if (isEnvironmentAssignment(token)) {
                index++;
                continue;
            }
            String lowerToken = token.toLowerCase(Locale.ROOT);
            if ("--".equals(lowerToken)) {
                return index + 1;
            }
            if (lowerToken.startsWith("-") && !"-".equals(lowerToken)) {
                if (envOptionConsumesNextToken(lowerToken)) {
                    index += 2;
                    continue;
                }
                index++;
                continue;
            }
            return index;
        }
        return index;
    }

    private boolean envOptionConsumesNextToken(String option) {
        if (option.startsWith("--")) {
            if (option.contains("=")) {
                return false;
            }
            return "--unset".equals(option)
                    || "--chdir".equals(option)
                    || "--split-string".equals(option)
                    || "--block-signal".equals(option)
                    || "--default-signal".equals(option)
                    || "--ignore-signal".equals(option);
        }
        if (option.charAt(0) != '-' || option.length() < 2) {
            return false;
        }
        return shortOptionClusterConsumesNextToken(option, "uCS");
    }

    private boolean shortOptionClusterConsumesNextToken(String option, String argTakingOptions) {
        for (int i = 1; i < option.length(); i++) {
            char shortOption = option.charAt(i);
            if (argTakingOptions.indexOf(shortOption) < 0) {
                continue;
            }
            // Example: -iu => u needs next token, -uroot => root is inline in same token.
            return i == option.length() - 1;
        }
        return false;
    }

    private boolean isEnvironmentAssignment(String token) {
        int equalsAt = token.indexOf('=');
        if (equalsAt <= 0) {
            return false;
        }
        char first = token.charAt(0);
        if (!(Character.isLetter(first) || first == '_')) {
            return false;
        }
        for (int i = 1; i < equalsAt; i++) {
            char c = token.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private String normalizeCommandName(String token) {
        String sanitized = sanitizeToken(token);
        if (sanitized.isEmpty()) {
            return "";
        }
        int slashAt = sanitized.lastIndexOf('/');
        if (slashAt >= 0 && slashAt < sanitized.length() - 1) {
            sanitized = sanitized.substring(slashAt + 1);
        }
        return sanitized.toLowerCase(Locale.ROOT);
    }

    private String sanitizeToken(String token) {
        String trimmed = (token == null) ? "" : token.trim();
        if (trimmed.length() < 2) {
            return trimmed;
        }
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\""))
                || (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private Long normalizeTimeoutOverride(Long timeoutSecondsOverride) {
        return (timeoutSecondsOverride != null && timeoutSecondsOverride > 0)
                ? timeoutSecondsOverride
                : null;
    }

    static String normalizeQueryCsvForToolResult(String command, String output) {
        return CommandResultPostProcessor.normalizeQueryCsvForToolResult(command, output);
    }

    private String getConfirmationPrompt(String pseudoCommand) {
        return CommandMarkers.confirmationPrompt(pseudoCommand);
    }

    private String getCurrentUsername() {
        return userContext.resolveUsernameOrAnonymous();
    }

    private String resolveAuditUsername(String usernameOverride) {
        return UsernameUtils.normalizeUsernameOrAnonymous(
                (usernameOverride != null && !usernameOverride.isBlank()) ? usernameOverride : getCurrentUsername());
    }

    private static ExecutorService createDefaultOutputCaptureExecutor() {
        AtomicInteger threadCounter = new AtomicInteger(1);
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, "cmd-output-capture-fallback-" + threadCounter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newCachedThreadPool(threadFactory);
    }

}
