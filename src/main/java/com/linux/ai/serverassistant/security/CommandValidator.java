package com.linux.ai.serverassistant.security;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Command validator - prevents shell injection attacks.
 *
 * Security strategy:
 * 1. Dangerous character detection (;&`$><)
 * 2. Command chaining detection (||, &&)
 * 3. Pipe whitelist validation
 * 4. Quote pairing check
 * 5. Blacklist command detection
 *
 * @author Claude Code
 */
@Component
public class CommandValidator {

    // Dangerous character pattern (excludes pipe '|', handled separately)
    // Includes \n and \r to prevent newline-based command injection via /bin/bash -c
    private static final Pattern DANGEROUS_CHARS = Pattern.compile("[;&`$><\\n\\r\\x00]");

    // Command chain pattern (|| or &&)
    private static final Pattern COMMAND_CHAIN = Pattern.compile("(\\|\\||&&)");
    private static final Pattern AWK_EXECUTION_PRIMITIVE =
            Pattern.compile("\\bsystem\\s*\\(|\\|\\s*getline|getline\\s*\\|", Pattern.CASE_INSENSITIVE);
    private static final Pattern SED_EXECUTION_PRIMITIVE =
            Pattern.compile("(^|[;\\s])[0-9,]*[erw](\\s|$)|s[^\\n]*/[^\\n]*/[a-zA-Z]*e[a-zA-Z]*",
                    Pattern.CASE_INSENSITIVE);

    // Explicitly blocked commands (rejected directly, no confirmation flow)
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "iptables", "ip6tables", "ufw",
            "dd", "mkfs", "fdisk",
            "reboot", "shutdown", "halt", "poweroff", "init"
    );
    // High-risk command blacklist (requires confirmation)
    private static final Set<String> HIGH_RISK_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "cp", "rsync", "tar", "zip", "unzip",
            "chmod", "chown",
            "useradd", "userdel", "usermod", "groupadd", "groupdel", "passwd",
            "mount", "umount",
            "crontab",
            "apt", "yum", "dnf"
    );
    private static final Set<String> SYSTEMCTL_MUTATING_ACTIONS = Set.of(
            "start", "stop", "restart", "reload", "try-restart",
            "reload-or-restart", "reload-or-try-restart",
            "enable", "disable", "mask", "unmask", "reenable",
            "link", "preset", "preset-all", "set-default", "isolate",
            "kill", "daemon-reload", "reexec", "revert",
            "reboot", "poweroff", "halt"
    );
    private static final Set<String> BLOCKED_SYSTEMCTL_ACTIONS = Set.of(
            "reboot", "poweroff", "halt"
    );
    private static final Set<String> SERVICE_MUTATING_ACTIONS = Set.of(
            "start", "stop", "restart", "reload", "force-reload",
            "try-restart", "condrestart", "enable", "disable"
    );
    private static final Set<String> SYSTEMCTL_OPTIONS_WITH_VALUE = Set.of(
            "-H", "--host",
            "-M", "--machine",
            "-p", "--property",
            "-t", "--type",
            "-s", "--signal",
            "-n", "--lines",
            "-o", "--output",
            "--state",
            "--root"
    );

    private static final Set<String> SUDO_OPTIONS_WITH_VALUE = Set.of(
            "-u", "--user", "-g", "--group", "-h", "--host",
            "-p", "--prompt", "-r", "--role", "-t", "--type",
            "-C", "--close-from", "-D", "--chdir");
    private static final Set<String> ENV_OPTIONS_WITH_VALUE = Set.of(
            "-u", "--unset", "-S", "--split-string", "-C", "--chdir", "--block-signal");
    private static final Set<String> TIME_OPTIONS_WITH_VALUE = Set.of(
            "-f", "--format", "-o", "--output");
    private static final Set<String> XARGS_OPTIONS_WITH_VALUE = Set.of(
            "-a", "--arg-file",
            "-d", "--delimiter",
            "-E", "--eof",
            "-I",
            "-L", "-l", "--max-lines",
            "-n", "--max-args",
            "-P", "--max-procs",
            "-s", "--max-chars",
            "--process-slot-var"
    );
    private static final Set<String> XARGS_OPTIONS_OPTIONAL_VALUE = Set.of(
            "-e", "-i", "--replace"
    );
    private static final Set<String> XARGS_OPTIONS_WITH_INLINE_VALUE = Set.of(
            "-a", "-d", "-E", "-e", "-I", "-i", "-L", "-l", "-n", "-P", "-s"
    );
    private static final Pattern SHELL_ASSIGNMENT_TOKEN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=.*$");
    private static final Set<String> SHELL_WRAPPER_COMMANDS = Set.of(
            "sh", "bash", "zsh", "dash", "ksh", "fish");
    private static final Set<String> INTERPRETERS_WITH_INLINE_EXECUTION = Set.of(
            "python", "python2", "python3", "pypy", "pypy3",
            "perl",
            "node", "nodejs", "deno",
            "ruby",
            "php",
            "lua", "luajit"
    );
    private static final Set<String> BLOCKED_COMMAND_PATTERNS = createBlockedCommandPatterns();

    // Allowed safe commands after a pipe
    private static final Set<String> PIPE_WHITELIST = Set.of(
            "grep", "cut", "sort", "uniq", "head", "tail",
            "wc", "tr", "more", "less", "cat",
            "awk", "sed", "xargs", "paste", "comm"
    );
    private static final Set<String> XARGS_COMMAND_WHITELIST = Set.of(
            "echo",
            "grep", "cut", "sort", "uniq", "head", "tail",
            "wc", "tr", "more", "less", "cat",
            "awk", "sed", "paste", "comm"
    );

    private final CommandTokenizer tokenizer;
    private final CommandArgumentScanner argumentScanner;
    private final DockerCommandParser dockerCommandParser;
    private final PathValidator pathValidator;

    public CommandValidator() {
        this.tokenizer = new CommandTokenizer();
        this.argumentScanner = new CommandArgumentScanner(tokenizer);
        this.dockerCommandParser = new DockerCommandParser(tokenizer, argumentScanner);
        this.pathValidator = new PathValidator(tokenizer);
    }

    /**
     * Validates whether a command is safe.
     *
     * @param command the command to execute
     * @return validation result
     */
    private static final int MAX_COMMAND_LENGTH = 8192;

    public ValidationResult validate(String command) {
        if (command == null || command.trim().isEmpty()) {
            return ValidationResult.invalid("命令不能為空");
        }
        if (command.length() > MAX_COMMAND_LENGTH) {
            return ValidationResult.invalid("命令過長（上限 " + MAX_COMMAND_LENGTH + " 字元）");
        }

        String trimmedCommand = command.trim();

        // Explicitly block heredoc/here-string syntax as shell wrapper execution vectors.
        if (tokenizer.containsUnquotedHereDocOperator(trimmedCommand)) {
            return ValidationResult.invalid("命令包含 heredoc/here-string 語法（<< 或 <<<），已阻止執行");
        }

        // 1. Check dangerous characters
        if (DANGEROUS_CHARS.matcher(trimmedCommand).find()) {
            return ValidationResult.invalid("命令包含危險字符（;&`$><），已阻止執行");
        }

        // 2. Check command chaining (|| or &&)
        if (COMMAND_CHAIN.matcher(trimmedCommand).find()) {
            return ValidationResult.invalid("命令包含命令鏈（|| 或 &&），已阻止執行");
        }

        List<String> unquotedPipeParts = tokenizer.splitByUnquotedPipes(trimmedCommand);

        // 3. Check pipe injection on unquoted pipes
        if (hasPipeInjection(unquotedPipeParts)) {
            return ValidationResult.invalid("檢測到管道符注入嘗試，已阻止執行");
        }

        // 4. If a pipe is present, validate the command after the pipe
        if (unquotedPipeParts.size() > 1) {
            ValidationResult pipeResult = validatePipeCommand(unquotedPipeParts);
            if (pipeResult instanceof Invalid) {
                return pipeResult;
            }
        }

        // 5. Check quote pairing
        if (!tokenizer.isQuotesBalanced(trimmedCommand)) {
            return ValidationResult.invalid("命令引號不配對");
        }

        String[] parts = tokenizer.tokenize(trimmedCommand);

        // 6. Check whether it is a high-risk command (requires confirmation)
        CommandHead head = extractCommandHead(parts);
        String firstCommand = head.command();
        if (SHELL_WRAPPER_COMMANDS.contains(firstCommand)) {
            return ValidationResult.invalid("命令已停用，不允許執行：" + firstCommand);
        }
        if (containsInlineInterpreterExecution(parts, head.tokenIndex(), firstCommand)) {
            return ValidationResult.invalid("命令已停用，不允許執行：" + firstCommand + "（inline script）");
        }
        if (isBlockedCommand(firstCommand)) {
            return ValidationResult.invalid("命令已停用，不允許執行：" + firstCommand);
        }
        if (HIGH_RISK_COMMANDS.contains(firstCommand)) {
            // 6a. Block rm/rmdir targeting protected system paths entirely
            if ("rm".equals(firstCommand) || "rmdir".equals(firstCommand)) {
                String protectedHit = pathValidator.checkProtectedPaths(trimmedCommand, head.tokenIndex());
                if (protectedHit != null) {
                    return ValidationResult.invalid("⛔ 拒絕刪除：" + protectedHit + " 是系統保護目錄，不允許刪除。");
                }
            }
            return ValidationResult.requiresConfirmation(firstCommand);
        }
        if ("systemctl".equals(firstCommand)) {
            if (containsBlockedSystemctlAction(parts, head.tokenIndex())) {
                return ValidationResult.invalid("命令已停用，不允許執行：systemctl");
            }
            String action = extractSystemctlMutatingAction(parts, head.tokenIndex());
            if (action != null) {
                if (BLOCKED_SYSTEMCTL_ACTIONS.contains(action)) {
                    return ValidationResult.invalid("命令已停用，不允許執行：systemctl " + action);
                }
                return ValidationResult.requiresConfirmation("systemctl " + action);
            }
        }
        if ("service".equals(firstCommand)) {
            String action = extractServiceMutatingAction(parts, head.tokenIndex());
            if (action != null) {
                return ValidationResult.requiresConfirmation("service " + action);
            }
        }
        if ("docker".equals(firstCommand)) {
            String dockerRiskAction = dockerCommandParser.extractHighRiskAction(trimmedCommand, head.tokenIndex());
            if (dockerRiskAction != null) {
                return ValidationResult.requiresConfirmation("docker " + dockerRiskAction);
            }
        }

        return ValidationResult.valid();
    }

    /**
     * Validates piped commands.
     */
    private ValidationResult validatePipeCommand(List<String> pipeParts) {
        for (int i = 1; i < pipeParts.size(); i++) {
            String pipedCommand = pipeParts.get(i).trim();
            String firstCmd = extractFirstPipeCommand(pipedCommand);

            // Commands after a pipe must be in the whitelist
            if (!PIPE_WHITELIST.contains(firstCmd)) {
                return ValidationResult.invalid(
                        "管道命令 '" + firstCmd + "' 不在允許的白名單中。"
                                + "允許的命令：" + String.join(", ", PIPE_WHITELIST)
                );
            }
            if ("xargs".equals(firstCmd)) {
                ValidationResult xargsResult = validateXargsPipeCommand(pipedCommand);
                if (xargsResult instanceof Invalid) {
                    return xargsResult;
                }
            }
            if ("awk".equals(firstCmd)) {
                ValidationResult awkResult = validateAwkPipeCommand(pipedCommand);
                if (awkResult instanceof Invalid) {
                    return awkResult;
                }
            }
            if ("sed".equals(firstCmd)) {
                ValidationResult sedResult = validateSedPipeCommand(pipedCommand);
                if (sedResult instanceof Invalid) {
                    return sedResult;
                }
            }
        }

        return ValidationResult.valid();
    }

    private ValidationResult validateXargsPipeCommand(String pipedCommand) {
        String[] parts = tokenizer.tokenize(pipedCommand);
        int commandIndex = findXargsCommandTokenIndex(parts, 1);
        if (commandIndex < 0 || commandIndex >= parts.length) {
            // xargs without explicit command defaults to `echo`, treat as read-only.
            return ValidationResult.valid();
        }

        String delegatedCommand = tokenizer.normalizeToken(parts[commandIndex], false);
        if (!XARGS_COMMAND_WHITELIST.contains(delegatedCommand)) {
            return ValidationResult.invalid(
                    "xargs 僅允許執行白名單命令，檢測到：'" + delegatedCommand + "'。"
                            + "允許的命令：" + String.join(", ", XARGS_COMMAND_WHITELIST)
            );
        }
        return ValidationResult.valid();
    }

    private ValidationResult validateAwkPipeCommand(String pipedCommand) {
        String[] parts = tokenizer.tokenize(pipedCommand);
        for (int i = 1; i < parts.length; i++) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isEmpty()) {
                continue;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            if ("-f".equals(raw) || "-f".equals(lower)
                    || "--file".equals(lower)
                    || lower.startsWith("--file=")
                    || (raw.startsWith("-f") && raw.length() > 2)) {
                return ValidationResult.invalid("awk 管線模式不允許載入外部腳本（-f/--file）。");
            }
            if (AWK_EXECUTION_PRIMITIVE.matcher(raw).find()) {
                return ValidationResult.invalid("awk 包含命令執行語法（system/getline pipe），已阻止執行。");
            }
        }
        return ValidationResult.valid();
    }

    private ValidationResult validateSedPipeCommand(String pipedCommand) {
        String[] parts = tokenizer.tokenize(pipedCommand);
        for (int i = 1; i < parts.length; i++) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isEmpty()) {
                continue;
            }
            String lower = raw.toLowerCase(Locale.ROOT);
            if ("-f".equals(raw) || "--file".equals(lower)
                    || lower.startsWith("--file=")
                    || (raw.startsWith("-f") && raw.length() > 2)) {
                return ValidationResult.invalid("sed 管線模式不允許載入外部腳本（-f/--file）。");
            }
            if ("-i".equals(raw) || "--in-place".equals(lower)
                    || lower.startsWith("--in-place=")
                    || (raw.startsWith("-i") && raw.length() > 2)) {
                return ValidationResult.invalid("sed 管線模式不允許 in-place 修改（-i/--in-place）。");
            }

            String scriptToken = null;
            if ("-e".equals(raw) || "--expression".equals(lower)) {
                if (i + 1 < parts.length) {
                    scriptToken = tokenizer.stripWrappingQuotes(parts[++i] == null ? "" : parts[i].trim());
                }
            } else if (raw.startsWith("-e") && raw.length() > 2) {
                scriptToken = raw.substring(2);
            } else if (lower.startsWith("--expression=")) {
                scriptToken = raw.substring(raw.indexOf('=') + 1);
            } else if (!raw.startsWith("-")) {
                scriptToken = raw;
            }

            if (scriptToken != null && SED_EXECUTION_PRIMITIVE.matcher(scriptToken).find()) {
                return ValidationResult.invalid("sed 包含可執行/可寫入語法（e/r/w），已阻止執行。");
            }
        }
        return ValidationResult.valid();
    }

    private boolean hasPipeInjection(List<String> pipeParts) {
        if (pipeParts == null || pipeParts.size() <= 1) return false;
        for (int i = 1; i < pipeParts.size(); i++) {
            String piped = pipeParts.get(i);
            if (piped == null) continue;
            String trimmed = piped.stripLeading();
            if (trimmed.isEmpty()) continue;
            char first = trimmed.charAt(0);
            if (first == ';' || first == '&' || first == '`' || first == '$') {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the first command token after skipping wrapper commands
     * like env/sudo and leading environment assignments.
     */
    private CommandHead extractCommandHead(String[] parts) {
        int idx = findCommandTokenIndex(parts, 0);
        if (idx < 0 || idx >= parts.length) {
            return new CommandHead("", -1);
        }
        return new CommandHead(tokenizer.normalizeToken(parts[idx], true), idx);
    }

    /**
     * Extracts the first command token for pipe whitelist checking.
     * This intentionally does not resolve path basenames, so `/tmp/grep`
     * cannot bypass the pipe whitelist.
     */
    private String extractFirstPipeCommand(String command) {
        String[] parts = tokenizer.tokenize(command);
        if (parts.length == 0) return "";
        return tokenizer.normalizeToken(parts[0], false);
    }

    private int findCommandTokenIndex(String[] parts, int start) {
        int i = Math.max(0, start);
        while (i < parts.length) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isEmpty()) {
                i++;
                continue;
            }

            String normalized = tokenizer.normalizeToken(raw, true);
            if (normalized.isEmpty()) {
                i++;
                continue;
            }

            if (isShellAssignmentToken(raw)) {
                i++;
                continue;
            }

            if ("env".equals(normalized)) {
                i = skipCommandPrefixWithOptions(parts, i + 1, ENV_OPTIONS_WITH_VALUE, Set.of("-u"), true);
                continue;
            }

            if ("sudo".equals(normalized)) {
                i = skipCommandPrefixWithOptions(parts, i + 1, SUDO_OPTIONS_WITH_VALUE,
                        Set.of("-u", "-g", "-h", "-p", "-r", "-t", "-C", "-D"), false);
                continue;
            }

            if ("command".equals(normalized)
                    || "builtin".equals(normalized)) {
                i = skipSimpleWrapperOptions(parts, i + 1);
                continue;
            }

            if ("nohup".equals(normalized)) {
                i++;
                continue;
            }

            if ("time".equals(normalized)) {
                i = skipCommandPrefixWithOptions(parts, i + 1, TIME_OPTIONS_WITH_VALUE, Set.of("-f", "-o"), false);
                continue;
            }

            return i;
        }
        return -1;
    }

    private int skipSimpleWrapperOptions(String[] parts, int start) {
        int i = Math.max(0, start);
        while (i < parts.length) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isEmpty()) {
                i++;
                continue;
            }
            if ("--".equals(raw)) return i + 1;
            if (raw.startsWith("-")) {
                i++;
                continue;
            }
            return i;
        }
        return i;
    }

    private int findXargsCommandTokenIndex(String[] parts, int start) {
        int i = Math.max(0, start);
        while (i < parts.length) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isEmpty()) {
                i++;
                continue;
            }

            if ("--".equals(raw)) {
                i++;
                break;
            }

            if (!raw.startsWith("-")) {
                return i;
            }

            if (consumesInlineXargsOptionValue(raw)) {
                i++;
                continue;
            }

            if (XARGS_OPTIONS_WITH_VALUE.contains(raw)) {
                i += 2;
                continue;
            }

            if (XARGS_OPTIONS_OPTIONAL_VALUE.contains(raw)) {
                i++;
                continue;
            }

            if (raw.startsWith("--") && raw.contains("=")) {
                i++;
                continue;
            }

            i++;
        }
        return i >= parts.length ? -1 : i;
    }

    private boolean consumesInlineXargsOptionValue(String token) {
        for (String option : XARGS_OPTIONS_WITH_INLINE_VALUE) {
            if (token.startsWith(option) && token.length() > option.length()) {
                return true;
            }
        }
        return false;
    }

    private int skipCommandPrefixWithOptions(
            String[] parts,
            int start,
            Set<String> optionsWithValue,
            Set<String> compactOptionsWithValue,
            boolean allowAssignments) {
        int i = Math.max(0, start);
        while (i < parts.length) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isEmpty()) {
                i++;
                continue;
            }
            if ("--".equals(raw)) return i + 1;

            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.startsWith("-")) {
                int compactValueTokenCount = compactOptionValueTokenCount(lower, compactOptionsWithValue);
                if (compactValueTokenCount >= 0) {
                    i += 1 + compactValueTokenCount;
                    continue;
                }
                if (lower.contains("=")) {
                    i++;
                    continue;
                }
                if (optionsWithValue.contains(raw) || optionsWithValue.contains(lower)) {
                    i += 2;
                    continue;
                }
                i++;
                continue;
            }
            if (allowAssignments && isShellAssignmentToken(raw)) {
                i++;
                continue;
            }
            return i;
        }
        return i;
    }

    /**
     * Returns:
     * -1: not a compact option token that requires a value
     *  0: value is attached in the same token (e.g. -ualice)
     *  1: value must be consumed from the next token (e.g. -u alice, -iu alice)
     */
    private int compactOptionValueTokenCount(String tokenLower, Set<String> compactOptionsWithValue) {
        if (tokenLower == null
                || compactOptionsWithValue == null
                || tokenLower.length() <= 1
                || !tokenLower.startsWith("-")
                || tokenLower.startsWith("--")) {
            return -1;
        }
        Set<Character> optionsRequiringValue = new HashSet<>();
        for (String prefix : compactOptionsWithValue) {
            if (prefix == null || prefix.length() != 2 || prefix.charAt(0) != '-') {
                continue;
            }
            optionsRequiringValue.add(Character.toLowerCase(prefix.charAt(1)));
        }
        if (optionsRequiringValue.isEmpty()) {
            return -1;
        }
        String cluster = tokenLower.substring(1);
        for (int i = 0; i < cluster.length(); i++) {
            char option = cluster.charAt(i);
            if (!optionsRequiringValue.contains(option)) {
                continue;
            }
            int charsAfterOption = cluster.length() - i - 1;
            return (charsAfterOption == 0) ? 1 : 0;
        }
        return -1;
    }

    private boolean isShellAssignmentToken(String token) {
        if (token == null || token.isBlank()) return false;
        return SHELL_ASSIGNMENT_TOKEN.matcher(token).matches();
    }

    private boolean containsInlineInterpreterExecution(String[] parts, int commandTokenIdx, String firstCommand) {
        if (parts == null || firstCommand == null || firstCommand.isBlank()) return false;
        if (!INTERPRETERS_WITH_INLINE_EXECUTION.contains(firstCommand)) return false;
        if (parts.length == 0) return false;

        int start = Math.max(0, commandTokenIdx + 1);
        for (int i = start; i < parts.length; i++) {
            String raw = tokenizer.stripWrappingQuotes(parts[i] == null ? "" : parts[i].trim());
            if (raw.isEmpty()) continue;
            if ("--".equals(raw)) return false;

            String lower = raw.toLowerCase(Locale.ROOT);
            if (isInlineExecFlag(firstCommand, lower)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInlineExecFlag(String interpreter, String token) {
        if (token == null || token.isBlank() || !token.startsWith("-")) return false;
        return switch (interpreter) {
            case "python", "python2", "python3", "pypy", "pypy3" ->
                    token.equals("-c") || token.startsWith("-c");
            case "perl" ->
                    token.equals("-e") || token.equals("-e-")
                            || token.equals("-E")
                            || token.startsWith("-e") || token.startsWith("-E");
            case "node", "nodejs", "deno" ->
                    token.equals("-e") || token.equals("--eval")
                            || token.startsWith("--eval=")
                            || token.equals("-p") || token.startsWith("-p");
            case "ruby" ->
                    token.equals("-e") || token.startsWith("-e");
            case "php" ->
                    token.equals("-r") || token.startsWith("-r");
            case "lua", "luajit" ->
                    token.equals("-e") || token.startsWith("-e");
            default -> false;
        };
    }

    /**
     * Checks whether a command is high-risk (requires confirmation).
     */
    public boolean isHighRiskCommand(String command) {
        if (command == null || command.trim().isEmpty()) return false;
        return validate(command).requiresConfirmation();
    }

    /**
     * Returns the high-risk command list (requires confirmation) for documentation.
     */
    public Set<String> getHighRiskCommands() {
        return new HashSet<>(HIGH_RISK_COMMANDS);
    }

    /**
     * Returns blocked commands only (includes command families and blocked wrappers/actions).
     */
    public Set<String> getBlockedCommands() {
        return new HashSet<>(BLOCKED_COMMAND_PATTERNS);
    }

    private static Set<String> createBlockedCommandPatterns() {
        Set<String> out = new HashSet<>(BLOCKED_COMMANDS);
        out.add("mkfs.*");
        out.addAll(SHELL_WRAPPER_COMMANDS);
        for (String action : BLOCKED_SYSTEMCTL_ACTIONS) {
            out.add("systemctl " + action);
        }
        return Set.copyOf(out);
    }

    private boolean isBlockedCommand(String commandName) {
        if (commandName == null || commandName.isBlank()) return false;
        if (BLOCKED_COMMANDS.contains(commandName)) return true;
        if (commandName.startsWith("mkfs.")) return true;
        return false;
    }

    private boolean containsBlockedSystemctlAction(String[] parts, int systemctlTokenIdx) {
        if (parts == null) return false;
        if (parts.length == 0 || systemctlTokenIdx < 0 || systemctlTokenIdx >= parts.length) return false;
        for (int i = systemctlTokenIdx + 1; i < parts.length; i++) {
            String token = tokenizer.normalizeToken(parts[i], false);
            if (token.isBlank()) continue;
            if (BLOCKED_SYSTEMCTL_ACTIONS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private String extractSystemctlMutatingAction(String[] parts, int systemctlTokenIdx) {
        if (parts == null) return null;
        if (parts.length == 0 || systemctlTokenIdx < 0 || systemctlTokenIdx >= parts.length) return null;

        int actionIdx = argumentScanner.findNextNonFlagArgument(parts, systemctlTokenIdx + 1, SYSTEMCTL_OPTIONS_WITH_VALUE);
        if (actionIdx < 0) return null;
        String action = tokenizer.normalizeToken(parts[actionIdx], false);
        return SYSTEMCTL_MUTATING_ACTIONS.contains(action) ? action : null;
    }

    private String extractServiceMutatingAction(String[] parts, int serviceTokenIdx) {
        if (parts == null) return null;
        if (parts.length == 0 || serviceTokenIdx < 0 || serviceTokenIdx >= parts.length) return null;

        int serviceNameIdx = argumentScanner.findNextNonFlagArgument(parts, serviceTokenIdx + 1, Set.of());
        if (serviceNameIdx < 0) return null;
        int actionIdx = argumentScanner.findNextNonFlagArgument(parts, serviceNameIdx + 1, Set.of());
        if (actionIdx < 0) return null;
        String action = tokenizer.normalizeToken(parts[actionIdx], false);
        return SERVICE_MUTATING_ACTIONS.contains(action) ? action : null;
    }

    private record CommandHead(String command, int tokenIndex) {}

    public sealed interface ValidationResult permits Valid, Invalid, RequiresConfirmation {
        static ValidationResult valid() {
            return new Valid();
        }

        static ValidationResult invalid(String errorMessage) {
            return new Invalid(errorMessage);
        }

        static ValidationResult requiresConfirmation(String commandName) {
            return new RequiresConfirmation(commandName);
        }

        default boolean isValid() {
            return !(this instanceof Invalid);
        }

        default boolean requiresConfirmation() {
            return this instanceof RequiresConfirmation;
        }

        default String getErrorMessage() {
            return this instanceof Invalid invalid ? invalid.errorMessage() : null;
        }

        default String getCommandName() {
            return this instanceof RequiresConfirmation confirmation ? confirmation.commandName() : null;
        }
    }

    public record Valid() implements ValidationResult {
        @Override
        public String toString() {
            return "Valid";
        }
    }

    public record Invalid(String errorMessage) implements ValidationResult {
        @Override
        public String toString() {
            return "Invalid: " + errorMessage;
        }
    }

    public record RequiresConfirmation(String commandName) implements ValidationResult {
        @Override
        public String toString() {
            return "Requires confirmation: " + commandName;
        }
    }
}
