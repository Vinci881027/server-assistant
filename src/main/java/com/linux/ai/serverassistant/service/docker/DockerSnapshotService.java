package com.linux.ai.serverassistant.service.docker;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Read-only Docker snapshot service for deterministic slash commands.
 *
 * This intentionally runs fixed commands (no user input) and returns structured data
 * for Markdown rendering.
 */
@Service
public class DockerSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DockerSnapshotService.class);

    private final CommandLogRepository commandLogRepository;
    private final UserContext userContext;

    @Autowired
    public DockerSnapshotService(CommandLogRepository commandLogRepository, UserContext userContext) {
        this.commandLogRepository = commandLogRepository;
        this.userContext = userContext;
    }

    public Map<String, Object> getSnapshot() {
        Map<String, Object> out = new HashMap<>();

        if (!commandExists("docker")) {
            out.put("error", "docker 未安裝或不在 PATH。");
            out.put("containers", List.of());
            out.put("stats", List.of());
            out.put("clientVersion", "-");
            out.put("serverVersion", "-");
            return out;
        }

        // Versions
        String clientV = runFixedCommand(List.of("docker", "version", "--format", "{{.Client.Version}}"));
        String serverV = runFixedCommand(List.of("docker", "version", "--format", "{{.Server.Version}}"));
        out.put("clientVersion", cleanSingleLineOrDash(clientV));
        out.put("serverVersion", cleanSingleLineOrDash(serverV));

        // Containers (running only; snapshot)
        String ps = runFixedCommand(List.of(
                "docker", "ps",
                "--format", "{{.Names}}|{{.Image}}|{{.Status}}|{{.Ports}}",
                "--no-trunc"
        ));
        out.put("containers", parsePipedRows(ps, List.of("name", "image", "status", "ports"), 50));

        // Stats (running containers)
        String stats = runFixedCommand(List.of(
                "docker", "stats",
                "--no-stream",
                "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}|{{.MemPerc}}"
        ));
        out.put("stats", parsePipedRows(stats, List.of("name", "cpu", "memUsage", "memPercent"), 50));

        // If server isn't reachable, docker version / ps / stats might include the error message.
        // Surface it once.
        String err = firstErrorLine(clientV, serverV, ps, stats);
        if (err != null) out.put("error", err);

        @SuppressWarnings("unchecked")
        List<?> containers = (List<?>) out.getOrDefault("containers", List.of());
        saveAuditLog("docker ps/stats (deterministic)", containers.size() + " containers", err == null);
        return out;
    }

    private List<Map<String, String>> parsePipedRows(String raw, List<String> keys, int limit) {
        List<Map<String, String>> rows = new ArrayList<>();
        if (raw == null) return rows;
        String t = raw.trim();
        if (t.isEmpty()) return rows;
        if (t.startsWith("[ERROR]")) return rows;
        if (looksLikeDockerErrorText(t)) return rows;

        int count = 0;
        for (String line : t.split("\\r?\\n")) {
            if (count >= limit) break;
            String l = (line == null) ? "" : line.trim();
            if (l.isEmpty()) continue;
            if (looksLikeDockerErrorText(l)) continue;
            String[] parts = l.split("\\|", -1);
            Map<String, String> m = new HashMap<>();
            for (int i = 0; i < keys.size(); i++) {
                String v = (i < parts.length) ? parts[i].trim() : "-";
                m.put(keys.get(i), v.isEmpty() ? "-" : v);
            }
            rows.add(m);
            count++;
        }
        return rows;
    }

    private String cleanSingleLineOrDash(String raw) {
        if (raw == null) return "-";
        String t = raw.trim();
        if (t.isEmpty()) return "-";
        if (t.startsWith("[ERROR]")) return "-";
        if (looksLikeDockerErrorText(t)) return "-";
        String first = t.split("\\r?\\n", 2)[0].trim();
        if (looksLikeDockerErrorText(first)) return "-";
        return first.isEmpty() ? "-" : first;
    }

    private String firstErrorLine(String... raws) {
        for (String raw : raws) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            if (t.startsWith("[ERROR]")) return t;
            // docker sometimes prints non-zero errors to stdout
            if (looksLikeDockerErrorText(t)) {
                return t.split("\\r?\\n", 2)[0].trim();
            }
        }
        return null;
    }

    private boolean looksLikeDockerErrorText(String text) {
        if (text == null || text.isBlank()) return false;
        String lower = text.toLowerCase();
        return lower.contains("cannot connect to the docker daemon")
                || lower.contains("is the docker daemon running")
                || lower.contains("error response from daemon")
                || lower.contains("permission denied while trying to connect")
                || lower.contains("got permission denied while trying to connect");
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
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "DockerSnapshotService");
        } catch (Exception ex) {
            log.error("[Audit] Failed to save docker snapshot log: {}", ex.getMessage());
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
                return "[ERROR] docker timeout";
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                return r.lines().collect(java.util.stream.Collectors.joining("\n"));
            }
        } catch (Exception e) {
            return "[ERROR] docker failed: " + e.getMessage();
        }
    }
}
