package com.linux.ai.serverassistant.service.system;

import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Process Monitoring Service
 *
 * Responsibilities:
 * - Monitor system processes
 * - Get list of high-load processes
 * - Provide detailed process information
 *
 * @author Claude Code - Phase 2 Refactoring
 */
@Service
public class ProcessMonitorService {

    private final SystemInfo si = new SystemInfo();
    private final OperatingSystem os = si.getOperatingSystem();

    /**
     * Gets the top N processes by CPU usage.
     *
     * @param limit Limit on the number of processes returned
     * @return List of process information (formatted strings)
     */
    public List<String> getTopProcesses(int limit) {
        List<OSProcess> procs = os.getProcesses(
                OperatingSystem.ProcessFiltering.ALL_PROCESSES,
                OperatingSystem.ProcessSorting.CPU_DESC,
                limit
        );

        return procs.stream()
                .map(p -> String.format("PID: %d | User: %s | CPU: %.1f%% | Mem: %.1f MB | Cmd: %s",
                        p.getProcessID(),
                        p.getUser(),
                        100d * p.getProcessCpuLoadCumulative(),
                        p.getResidentSetSize() / 1_048_576.0,
                        p.getCommandLine()))
                .collect(Collectors.toList());
    }

    /**
     * Gets the top 5 processes by CPU usage (default).
     *
     * @return List of top 5 process information
     */
    public List<String> getTopProcesses() {
        return getTopProcesses(5);
    }

    /**
     * Gets the top N processes by memory usage.
     *
     * @param limit Limit on the number of processes returned
     * @return List of process information (formatted strings)
     */
    public List<String> getTopMemoryProcesses(int limit) {
        // OSHI does not provide a direct option to sort by memory, so manual sorting is required
        List<OSProcess> procs = os.getProcesses(
                OperatingSystem.ProcessFiltering.ALL_PROCESSES,
                OperatingSystem.ProcessSorting.CPU_DESC,
                0  // Get all processes
        );

        return procs.stream()
                .sorted((a, b) -> Long.compare(b.getResidentSetSize(), a.getResidentSetSize()))
                .limit(limit)
                .map(p -> String.format("PID: %d | User: %s | Mem: %.1f MB | CPU: %.1f%% | Cmd: %s",
                        p.getProcessID(),
                        p.getUser(),
                        p.getResidentSetSize() / 1_048_576.0,
                        100d * p.getProcessCpuLoadCumulative(),
                        p.getCommandLine()))
                .collect(Collectors.toList());
    }

    /**
     * Structured top-N by CPU usage for deterministic slash commands.
     */
    public List<Map<String, Object>> getTopCpuProcessInfos(int limit) {
        int lim = Math.max(1, limit);
        List<OSProcess> procs = os.getProcesses(
                OperatingSystem.ProcessFiltering.ALL_PROCESSES,
                OperatingSystem.ProcessSorting.CPU_DESC,
                lim
        );

        return procs.stream().map(p -> {
            Map<String, Object> m = new HashMap<>();
            m.put("pid", p.getProcessID());
            m.put("user", safe(p.getUser()));
            m.put("cpuPercent", String.format("%.1f%%", 100d * p.getProcessCpuLoadCumulative()));
            double memGb = p.getResidentSetSize() / 1_073_741_824.0;
            m.put("memGB", String.format("%.2f", memGb));
            m.put("memMB", String.format("%.1f", p.getResidentSetSize() / 1_048_576.0)); // legacy
            m.put("cmd", safe(truncate(p.getCommandLine(), 120)));
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * Structured top-N by memory usage for deterministic slash commands.
     */
    public List<Map<String, Object>> getTopMemoryProcessInfos(int limit) {
        int lim = Math.max(1, limit);
        List<OSProcess> procs = os.getProcesses(
                OperatingSystem.ProcessFiltering.ALL_PROCESSES,
                OperatingSystem.ProcessSorting.CPU_DESC,
                0
        );

        return procs.stream()
                .sorted((a, b) -> Long.compare(b.getResidentSetSize(), a.getResidentSetSize()))
                .limit(lim)
                .map(p -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("pid", p.getProcessID());
                    m.put("user", safe(p.getUser()));
                    m.put("cpuPercent", String.format("%.1f%%", 100d * p.getProcessCpuLoadCumulative()));
                    double memGb = p.getResidentSetSize() / 1_073_741_824.0;
                    m.put("memGB", String.format("%.2f", memGb));
                    m.put("memMB", String.format("%.1f", p.getResidentSetSize() / 1_048_576.0)); // legacy
                    m.put("cmd", safe(truncate(p.getCommandLine(), 120)));
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Gets detailed process information by PID.
     *
     * @param pid Process ID
     * @return Detailed process information, or null if not found
     */
    public OSProcess getProcessByPid(int pid) {
        return os.getProcess(pid);
    }

    /**
     * Gets the total number of system processes.
     *
     * @return Total process count
     */
    public int getProcessCount() {
        return os.getProcessCount();
    }

    /**
     * Gets the total number of system threads.
     *
     * @return Total thread count
     */
    public int getThreadCount() {
        return os.getThreadCount();
    }

    private String safe(String s) {
        if (s == null) return "-";
        String t = s.trim();
        return t.isEmpty() ? "-" : t;
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        if (maxLen <= 0) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
}
