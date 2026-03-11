package com.linux.ai.serverassistant.service.command;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Command optimizer for AI tool flows.
 *
 * Keeps optimization rules out of CommandExecutionService so execution logic
 * can stay focused on validation and process orchestration.
 */
final class CommandOptimizer {

    private static final Map<String, String> OPTIMIZED_COMMANDS;
    private static final Map<String, String> OPTIMIZED_PREFIX_COMMANDS;
    private static final String NVIDIA_SMI_GPU_QUERY_FIELDS =
            "index,name,temperature.gpu,utilization.gpu,utilization.memory,memory.used,memory.total,power.draw,power.limit";

    static {
        Map<String, String> exact = new HashMap<>();
        Map<String, String> prefix = new LinkedHashMap<>();

        // Process & System
        String psCmd = "ps -eo pid,user,pcpu,pmem,comm --sort=-pcpu | head -n 20";
        exact.put("ps", psCmd);
        exact.put("ps aux", psCmd);
        exact.put("ps -ef", psCmd);
        exact.put("top", "top -b -n 1 | head -n 20");

        // Docker
        exact.put("docker ps", "docker ps --format \"table {{.ID}}|{{.Image}}|{{.Status}}|{{.Names}}\"");
        String dockerImagesCmd = "docker images --format \"table {{.Repository}}|{{.Tag}}|{{.ID}}|{{.Size}}\"";
        exact.put("docker images", dockerImagesCmd);
        exact.put("docker image ls", dockerImagesCmd);
        exact.put("docker stats", "docker stats --no-stream --format \"table {{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}\" | head -n 16");

        // Network
        exact.put("netstat", "netstat -tuln");
        exact.put("ss", "ss -tuln");
        exact.put("ss -tulnp", "ss -tuln");
        exact.put("ss -s", "ss -s");
        exact.put("ip a", "ip -br addr");
        exact.put("ip addr", "ip -br addr");
        exact.put("ip address", "ip -br addr");
        exact.put("ip link", "ip -br link");
        exact.put("ip route", "ip route show default");

        // Disk & Memory
        exact.put("df", "df -h");
        exact.put("free", "free -h");
        exact.put("du", "du -h --max-depth=1");
        exact.put("lsblk", "lsblk -o NAME,SIZE,TYPE,FSTYPE,MOUNTPOINT");
        exact.put("findmnt", "findmnt -o TARGET,SOURCE,FSTYPE,OPTIONS");
        exact.put("mount", "findmnt -o TARGET,SOURCE,FSTYPE,OPTIONS");

        // Logs & History
        exact.put("dmesg", "dmesg | tail -n 20");
        exact.put("journalctl", "journalctl -n 50 --no-pager");
        exact.put("last", "last | head -n 20");
        exact.put("lsof", "lsof | head -n 20");
        exact.put("uptime", "uptime -p");
        exact.put("w", "w -h");
        exact.put("systemctl status", "systemctl --failed --no-pager");

        // GPU
        exact.put(
                "nvidia-smi",
                "nvidia-smi --query-gpu=" + NVIDIA_SMI_GPU_QUERY_FIELDS + " --format=csv,noheader,nounits");

        // Prefix rules for common variants (rule order matters).
        prefix.put("df ", "df -h");
        prefix.put("free ", "free -h");
        prefix.put("dmesg ", "dmesg | tail -n 20");
        prefix.put("journalctl ", "journalctl -n 50 --no-pager");
        prefix.put("last ", "last | head -n 20");
        prefix.put("lsof ", "lsof | head -n 20");
        prefix.put("ss ", "ss -tuln");

        OPTIMIZED_COMMANDS = Collections.unmodifiableMap(exact);
        OPTIMIZED_PREFIX_COMMANDS = Collections.unmodifiableMap(prefix);
    }

    String optimizeForAi(String command) {
        if (command == null) return null;
        String normalized = normalizeCommand(command);
        if (normalized.isEmpty()) return normalized;

        // 1) Exact normalized lookup
        String exact = OPTIMIZED_COMMANDS.get(normalized);
        if (exact != null) return exact;

        String lower = normalized.toLowerCase(Locale.ROOT);

        // 2) Structured rules with argument preservation where safe
        if (lower.equals("ip addr show") || lower.equals("ip address show")) {
            return "ip -br addr";
        }
        if (lower.startsWith("ip addr show ") || lower.startsWith("ip address show ")) {
            String suffix = lower.startsWith("ip addr show ")
                    ? normalized.substring("ip addr show ".length()).trim()
                    : normalized.substring("ip address show ".length()).trim();
            if (!suffix.isBlank()) {
                return "ip -br addr show " + suffix;
            }
            return "ip -br addr";
        }
        if (lower.equals("ip link show")) {
            return "ip -br link";
        }
        if (lower.startsWith("ip link show ")) {
            String suffix = normalized.substring("ip link show ".length()).trim();
            if (!suffix.isBlank()) {
                return "ip -br link show " + suffix;
            }
            return "ip -br link";
        }
        if (lower.equals("ip route show")) {
            return "ip route show default";
        }
        if (lower.equals("systemctl status")) {
            return "systemctl --failed --no-pager";
        }
        if (lower.startsWith("systemctl status ")) {
            String unit = normalized.substring("systemctl status ".length()).trim();
            if (!unit.isBlank()) {
                return "systemctl status " + unit + " --no-pager | head -n 60";
            }
        }

        // 3) Prefix rewrite fallback for common verbose forms
        for (Map.Entry<String, String> e : OPTIMIZED_PREFIX_COMMANDS.entrySet()) {
            if (lower.startsWith(e.getKey())) {
                return e.getValue();
            }
        }

        return normalized;
    }

    private String normalizeCommand(String command) {
        return command == null ? "" : command.trim().replaceAll("\\s+", " ");
    }
}
