package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import org.springframework.stereotype.Service;

import static com.linux.ai.serverassistant.util.ToolResultUtils.extractToolResult;
import static com.linux.ai.serverassistant.util.ToolResultUtils.formatExecutionResult;

/**
 * System update helper service.
 *
 * Keeps package-manager update checks in one place so the rest of the system
 * can call a single facade method.
 */
@Service
public class SystemdService {

    private final CommandExecutionService commandExecutionService;

    public SystemdService(CommandExecutionService commandExecutionService) {
        this.commandExecutionService = commandExecutionService;
    }

    /**
     * Checks for available system updates.
     *
     * Automatically detects the package manager (apt for Debian/Ubuntu or yum for RHEL/CentOS)
     * and checks for available updates without installing them.
     *
     * @return formatted list of available updates or message indicating system is up to date
     */
    public String checkSystemUpdates() {
        // Detect package manager and run update check via trusted root path
        // (these commands contain shell metacharacters like > and && that CommandValidator blocks)
        String checkCmd;
        String aptCheck = commandExecutionService.execute(
                "which apt",
                CommandExecutionService.ExecutionOptions.builder().trustedRoot().build());
        if (aptCheck.contains("apt")) {
            checkCmd = "apt update > /dev/null 2>&1 && apt list --upgradable 2>/dev/null";
        } else {
            String yumCheck = commandExecutionService.execute(
                    "which yum",
                    CommandExecutionService.ExecutionOptions.builder().trustedRoot().build());
            if (yumCheck.contains("yum")) {
                checkCmd = "yum check-update";
            } else {
                return formatExecutionResult("無法辨識的系統套件管理器，目前僅支援 Debian/Ubuntu (apt) 或 RHEL/CentOS (yum)。");
            }
        }

        String result = commandExecutionService.execute(
                checkCmd,
                CommandExecutionService.ExecutionOptions.builder().trustedRoot().build());
        String content = extractToolResult(result, result, 2);
        if (content.isBlank()) {
            return formatExecutionResult("系統目前已是最新狀態，無須更新。");
        }
        return result;
    }

    /**
     * Checks if system updates are available.
     *
     * @return formatted update information
     */
    public String getAvailableUpdates() {
        return checkSystemUpdates();
    }

}
