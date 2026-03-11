package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.entity.CommandType;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Command audit log service.
 *
 * Separates persistence and masking logic from command execution flow.
 */
public final class CommandAuditService {

    private static final Logger log = LoggerFactory.getLogger(CommandAuditService.class);
    private final CommandLogRepository commandLogRepository;

    CommandAuditService(CommandLogRepository commandLogRepository) {
        this.commandLogRepository = commandLogRepository;
    }

    void saveCommandLog(String command, String output, boolean success, Integer exitCode, String username) {
        try {
            CommandLog cmdLog = new CommandLog();
            cmdLog.setUsername(username);

            // Sensitive information masking: if command contains SSH Key or password, mask it
            String maskedCommand = command;
            if (command != null && (command.contains("ssh-rsa") || command.contains("ssh-ed25519"))) {
                maskedCommand = command.replaceAll(
                        "(ssh-(rsa|ed25519|dss)|ecdsa-sha2-nistp256)\\s+[A-Za-z0-9+/=]{10,}",
                        "$1 [MASKED_KEY]");
            }
            cmdLog.setCommand(maskedCommand);

            // Limit output length to avoid database field overflow
            cmdLog.setOutput(truncateOutput(output));
            cmdLog.setSuccess(success);
            cmdLog.setExitCode(exitCode);
            cmdLog.setCommandType(classifyCommandType(command));
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "CommandAuditService");
        } catch (Exception ex) {
            log.error("Failed to save audit log: {}", ex.getMessage());
        }
    }

    /**
     * Classifies a command as MODIFY or READ based on its leading keyword.
     */
    public static CommandType classifyCommandType(String command) {
        if (command == null || command.isBlank()) return CommandType.READ;
        String trimmed = command.stripLeading().toLowerCase();

        // Modification keywords — order doesn't matter, just check prefix
        String[] modifyPrefixes = {
                "rm ", "rm\t", "rmdir ", "mv ", "cp ",
                "chmod ", "chown ", "chgrp ",
                "mkdir ", "touch ",
                "useradd ", "userdel ", "usermod ", "chpasswd",
                "systemctl start ", "systemctl stop ", "systemctl restart ",
                "systemctl enable ", "systemctl disable ",
                "kill ", "killall ", "pkill ",
                "reboot", "shutdown", "poweroff",
                "apt install ", "apt remove ", "apt upgrade", "apt update",
                "yum install ", "yum remove ", "yum update",
                "tee ", "tee\t",
                "dd ", "mkfs",
                "mount ", "umount ",
                "crontab ",
                "sed -i ", "sed -i'",
        };

        for (String prefix : modifyPrefixes) {
            if (trimmed.startsWith(prefix) || trimmed.equals(prefix.stripTrailing())) {
                return CommandType.MODIFY;
            }
        }

        // Check for redirection operators that imply writing
        if (trimmed.contains(" > ") || trimmed.contains(" >> ")) {
            return CommandType.MODIFY;
        }

        return CommandType.READ;
    }

    public static String truncateOutput(String output) {
        if (output == null) return null;
        return output.length() > 4000 ? output.substring(0, 4000) + "..." : output;
    }
}
