package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.entity.CommandType;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.AuditLogPersistenceService;
import com.linux.ai.serverassistant.service.command.CommandAuditService;
import com.linux.ai.serverassistant.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Read-only port snapshot service for deterministic slash commands.
 *
 * Data source preference:
 * 1) ss
 * 2) netstat
 */
@Service
public class PortStatusService {

    private static final Logger log = LoggerFactory.getLogger(PortStatusService.class);

    private final CommandLogRepository commandLogRepository;
    private final UserContext userContext;

    @Autowired
    public PortStatusService(CommandLogRepository commandLogRepository, UserContext userContext) {
        this.commandLogRepository = commandLogRepository;
        this.userContext = userContext;
    }

    public Map<String, Object> getListeningPorts() {
        return getListeningPorts(command -> commandExists(command), this::runFixedCommand);
    }

    Map<String, Object> getListeningPorts(Function<String, Boolean> commandExistsProbe,
                                          Function<List<String>, String> fixedCommandRunner) {
        Map<String, Object> out = new HashMap<>();

        boolean hasSs = commandExistsProbe != null && Boolean.TRUE.equals(commandExistsProbe.apply("ss"));
        boolean hasNetstat = commandExistsProbe != null && Boolean.TRUE.equals(commandExistsProbe.apply("netstat"));
        if (!hasSs && !hasNetstat) {
            out.put("error", "找不到 `ss` 或 `netstat`，無法取得 port 資訊。");
            out.put("rows", List.of());
            out.put("source", "-");
            return out;
        }

        String raw = null;
        String source = "-";
        String ssFailure = null;

        if (hasSs) {
            raw = fixedCommandRunner.apply(List.of("ss", "-H", "-tuln"));
            source = "ss";
            if (isFailedExecution(raw)) {
                ssFailure = formatFailure("ss", raw);
                raw = null;
                source = "-";
            }
        }
        if (raw == null && hasNetstat) {
            raw = fixedCommandRunner.apply(List.of("netstat", "-tuln"));
            source = "netstat";
        }

        if (raw == null) {
            out.put("error", ssFailure != null ? ssFailure : "port 指令執行失敗。");
            out.put("rows", List.of());
            out.put("source", source);
            return out;
        }
        if (isFailedExecution(raw)) {
            String netstatFailure = formatFailure(source, raw);
            if (ssFailure != null) {
                out.put("error", ssFailure + "；" + netstatFailure);
                out.put("source", "ss,netstat");
            } else {
                out.put("error", netstatFailure);
                out.put("source", source);
            }
            out.put("rows", List.of());
            return out;
        }

        List<Map<String, String>> rows = "ss".equals(source) ? parseSs(raw) : parseNetstat(raw);
        rows.sort(Comparator
                .comparingInt((Map<String, String> r) -> parsePortInt(r.get("port")))
                .thenComparing(r -> r.getOrDefault("proto", ""))
                .thenComparing(r -> r.getOrDefault("addr", "")));

        out.put("rows", rows);
        out.put("source", source);

        saveAuditLog(source + " (deterministic)", rows.size() + " listening ports", true);
        return out;
    }

    private boolean isFailedExecution(String raw) {
        if (raw == null) return true;
        String trimmed = raw.trim();
        return trimmed.startsWith("[ERROR]");
    }

    private String formatFailure(String source, String raw) {
        String prefix = (source == null || source.isBlank()) ? "port" : source;
        if (raw == null || raw.isBlank()) return prefix + " 執行失敗";
        return prefix + " 執行失敗: " + raw.trim();
    }

    private List<Map<String, String>> parseSs(String raw) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String l = line == null ? "" : line.trim();
            if (l.isEmpty()) continue;
            String[] parts = l.split("\\s+");
            // netid state recv-q send-q local peer
            if (parts.length < 6) continue;

            String proto = parts[0];
            String state = parts[1];
            String local = parts[4];
            AddrPort ap = splitAddrPort(local);

            Map<String, String> m = new HashMap<>();
            m.put("proto", proto.isEmpty() ? "-" : proto);
            m.put("state", state.isEmpty() ? "-" : state);
            m.put("addr", ap.addr());
            m.put("port", ap.port());
            rows.add(m);
        }
        return rows;
    }

    private List<Map<String, String>> parseNetstat(String raw) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (String line : raw.split("\\r?\\n")) {
            String l = line == null ? "" : line.trim();
            if (l.isEmpty()) continue;
            if (!(l.startsWith("tcp") || l.startsWith("udp"))) continue;
            String[] parts = l.split("\\s+");
            // proto recv-q send-q local foreign state?
            if (parts.length < 4) continue;

            String proto = parts[0];
            String local = parts[3];
            String state = "-";
            if (parts.length >= 6) state = parts[5];

            AddrPort ap = splitAddrPort(local);
            Map<String, String> m = new HashMap<>();
            m.put("proto", proto.isEmpty() ? "-" : proto);
            m.put("state", state.isEmpty() ? "-" : state);
            m.put("addr", ap.addr());
            m.put("port", ap.port());
            rows.add(m);
        }
        return rows;
    }

    private record AddrPort(String addr, String port) {}

    private AddrPort splitAddrPort(String local) {
        if (local == null) return new AddrPort("-", "-");
        String t = local.trim();
        if (t.isEmpty()) return new AddrPort("-", "-");

        // ss may emit bracketed IPv6: [::]:22
        int idx = t.lastIndexOf(':');
        if (idx <= 0 || idx == t.length() - 1) return new AddrPort(t, "-");

        String addr = t.substring(0, idx).trim();
        String port = t.substring(idx + 1).trim();

        // keep [::] as-is; for netstat ":::22", addr becomes "::" which is OK
        if (addr.isEmpty()) addr = "-";
        if (port.isEmpty()) port = "-";
        return new AddrPort(addr, port);
    }

    private int parsePortInt(String port) {
        if (port == null) return Integer.MAX_VALUE;
        String t = port.trim();
        if (t.isEmpty()) return Integer.MAX_VALUE;
        if (t.equals("*") || t.equals("-")) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private void saveAuditLog(String command, String output, boolean success) {
        try {
            String currentUser = userContext.resolveUsernameOrAnonymous();
            if (currentUser == null || currentUser.isBlank() || "anonymous".equalsIgnoreCase(currentUser)) {
                currentUser = "system";
            }

            CommandLog cmdLog = new CommandLog();
            cmdLog.setUsername(currentUser);
            cmdLog.setCommand(command);
            cmdLog.setOutput(CommandAuditService.truncateOutput(output));
            cmdLog.setSuccess(success);
            cmdLog.setCommandType(CommandType.READ);
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "PortStatusService");
        } catch (Exception ex) {
            log.error("[Audit] Failed to save port status log: {}", ex.getMessage());
        }
    }

    private boolean commandExists(String command) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", "command -v " + command)
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private String runFixedCommand(List<String> cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(4, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "[ERROR] port timeout";
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return r.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return "[ERROR] port failed: " + e.getMessage();
        }
    }
}
