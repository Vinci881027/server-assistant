package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.entity.CommandType;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.AuditLogPersistenceService;
import com.linux.ai.serverassistant.service.command.CommandAuditService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.GraphicsCard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * GPU status query service.
 *
 * Prefer vendor tools (nvidia-smi).
 * If unavailable, fall back to OSHI hardware info.
 */
@Service
public class GpuStatusService {

    private static final Logger log = LoggerFactory.getLogger(GpuStatusService.class);
    private static final long COMMAND_EXISTS_TIMEOUT_SECONDS = 2L;
    private static final long COMMAND_TIMEOUT_SECONDS = 5L;

    private final CommandLogRepository commandLogRepository;
    private final UserContext userContext;

    @Autowired
    public GpuStatusService(CommandLogRepository commandLogRepository, UserContext userContext) {
        this.commandLogRepository = commandLogRepository;
        this.userContext = userContext;
    }

    private static final String NVIDIA_SMI_QUERY =
            "nvidia-smi --query-gpu=index,name,temperature.gpu,utilization.gpu,utilization.memory," +
            "memory.used,memory.total,power.draw,power.limit --format=csv,noheader";

    private static final String NVIDIA_SMI_FULL = "nvidia-smi";

    private static final String NVIDIA_SMI_UUID_INDEX_QUERY =
            "nvidia-smi --query-gpu=uuid,index,name --format=csv,noheader";

    private static final String NVIDIA_SMI_PROCESS_QUERY =
            "nvidia-smi --query-compute-apps=gpu_uuid,pid,process_name,used_memory --format=csv,noheader";

    private final SystemInfo systemInfo = new SystemInfo();

    private record CommandResult(boolean success, String output, String errorMessage) {
        static CommandResult ok(String output) {
            return new CommandResult(true, output == null ? "" : output, null);
        }

        static CommandResult error(String errorMessage) {
            return new CommandResult(false, null, errorMessage);
        }
    }

    public String getGpuStatus() {
        String result = getGpuStatusInternal();
        boolean success = result != null
                && !result.contains("[ERROR]")
                && !result.contains(CommandMarkers.SECURITY_VIOLATION);
        saveAuditLog("nvidia-smi (deterministic)", result, success);
        return result;
    }

    private String getGpuStatusInternal() {
        if (commandExists("nvidia-smi")) {
            CompletableFuture<CommandResult> nvidiaSmiFuture =
                    CompletableFuture.supplyAsync(() -> runCommand(NVIDIA_SMI_FULL));
            CompletableFuture<CommandResult> queryFuture =
                    CompletableFuture.supplyAsync(() -> runCommand(NVIDIA_SMI_QUERY));
            CompletableFuture<CommandResult> uuidMapFuture =
                    CompletableFuture.supplyAsync(() -> runCommand(NVIDIA_SMI_UUID_INDEX_QUERY));
            CompletableFuture<CommandResult> procsFuture =
                    CompletableFuture.supplyAsync(() -> runCommand(NVIDIA_SMI_PROCESS_QUERY));

            CommandResult nvidiaSmiRes = joinCommandResult(nvidiaSmiFuture, NVIDIA_SMI_FULL);
            if (!nvidiaSmiRes.success()) return nvidiaSmiRes.errorMessage();

            CommandResult queryRes = joinCommandResult(queryFuture, NVIDIA_SMI_QUERY);
            if (!queryRes.success()) return queryRes.errorMessage();

            CommandResult uuidMapRes = joinCommandResult(uuidMapFuture, NVIDIA_SMI_UUID_INDEX_QUERY);
            if (!uuidMapRes.success()) return uuidMapRes.errorMessage();

            CommandResult procsRes = joinCommandResult(procsFuture, NVIDIA_SMI_PROCESS_QUERY);
            if (!procsRes.success()) return procsRes.errorMessage();

            String nvidiaSmi = nvidiaSmiRes.output();
            String query = queryRes.output();
            String uuidMap = uuidMapRes.output();
            String procs = procsRes.output();
            String smiProcBlock = extractNvidiaProcessesBlock(nvidiaSmi);

            StringBuilder sb = new StringBuilder();
            String headerLine = extractNvidiaHeaderLine(nvidiaSmi);
            if (headerLine != null && !headerLine.isBlank()) sb.append(headerLine.trim()).append('\n');
            sb.append("#GPU\n");
            if (query != null && !query.isBlank()) sb.append(query.trim()).append('\n');
            sb.append("#GPU_UUID\n");
            if (uuidMap != null && !uuidMap.isBlank()) sb.append(uuidMap.trim()).append('\n');
            sb.append("#PROCESSES\n");
            if (procs != null && !procs.isBlank()) sb.append(procs.trim()).append('\n');
            sb.append("#NVIDIA_SMI_PROCESSES\n");
            if (smiProcBlock != null && !smiProcBlock.isBlank()) sb.append(smiProcBlock.trim()).append('\n');

            String out = sb.toString().trim();
            if (!out.isEmpty() && !query.isBlank()) return out;

            return "[ERROR] GPU 查詢結果為空。";
        }

        List<GraphicsCard> cards = systemInfo.getHardware().getGraphicsCards();
        if (cards == null || cards.isEmpty()) {
            return "未偵測到 GPU，且系統中未安裝 nvidia-smi。";
        }

        String header = "name,vendor,vramMB,version";
        String body = cards.stream()
                .map(card -> String.format("%s,%s,%d,%s",
                        safe(card.getName()),
                        safe(card.getVendor()),
                        card.getVRam() / (1024 * 1024),
                        safe(card.getVersionInfo())))
                .collect(Collectors.joining("\n"));

        return header + "\n" + body;
    }

    private CommandResult joinCommandResult(CompletableFuture<CommandResult> future, String command) {
        try {
            return future.join();
        } catch (CompletionException ex) {
            String msg = ex.getCause() == null ? ex.getMessage() : ex.getCause().getMessage();
            return CommandResult.error("[ERROR] GPU 查詢指令執行失敗: " + command + " (" + msg + ")");
        }
    }

    private boolean commandExists(String command) {
        try {
            String safeCommand = command.replaceAll("[^a-zA-Z0-9._-]", "");
            Process process = new ProcessBuilder("/bin/sh", "-c", "command -v -- " + safeCommand)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(COMMAND_EXISTS_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private CommandResult runCommand(String command) {
        try {
            Process process = new ProcessBuilder("/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return CommandResult.error("[ERROR] GPU 查詢指令逾時: " + command);
            }

            int exitCode = process.exitValue();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String output = reader.lines().collect(Collectors.joining("\n"));
                if (exitCode != 0) {
                    String detail = output == null ? "" : output.trim();
                    if (detail.isBlank()) {
                        detail = "無額外輸出";
                    }
                    detail = limitErrorDetail(detail, 300);
                    return CommandResult.error("[ERROR] GPU 查詢指令執行失敗 (exit " + exitCode + "): " + detail);
                }
                return CommandResult.ok(output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return CommandResult.error("[ERROR] GPU 查詢指令被中斷");
        } catch (Exception e) {
            return CommandResult.error("[ERROR] 無法執行 GPU 查詢指令: " + e.getMessage());
        }
    }

    private String limitErrorDetail(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "...";
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
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "GpuStatusService");
        } catch (Exception ex) {
            log.error("[Audit] Failed to save GPU status log: {}", ex.getMessage());
        }
    }

    private String safe(String value) {
        return value == null ? "" : value.replace("\n", " ").trim();
    }

    private String extractNvidiaHeaderLine(String nvidiaSmiOutput) {
        if (nvidiaSmiOutput == null) return null;
        // Typical line:
        // "NVIDIA-SMI 535.129.03    Driver Version: 535.129.03    CUDA Version: 12.2"
        for (String line : nvidiaSmiOutput.split("\\r?\\n")) {
            String t = (line == null) ? "" : line.trim();
            if (t.isEmpty()) continue;
            if (t.contains("Driver Version:") && t.contains("CUDA Version:") && t.toUpperCase().contains("NVIDIA-SMI")) {
                return t;
            }
        }
        return null;
    }

    private String extractNvidiaProcessesBlock(String nvidiaSmiOutput) {
        if (nvidiaSmiOutput == null) return null;
        String[] lines = nvidiaSmiOutput.split("\\r?\\n");
        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            String t = (lines[i] == null) ? "" : lines[i].trim();
            if (t.isEmpty()) continue;
            // Typical marker line includes: "| Processes:" or "Processes:"
            if (t.contains("Processes:")) {
                start = i;
                break;
            }
        }
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            String l = lines[i];
            if (l == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        return sb.toString().trim();
    }
}
