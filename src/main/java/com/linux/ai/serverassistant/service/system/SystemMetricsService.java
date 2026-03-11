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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.HWDiskStore;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;
import oshi.software.os.OSFileStore;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * System Metrics Monitoring Service
 *
 * Responsibilities:
 * - Collect metrics such as system uptime, load, memory, temperature, etc.
 * - Provide disk usage information
 * - Query system logs
 *
 * @author Claude Code - Phase 2 Refactoring
 */
@Service
public class SystemMetricsService {

    private static final Logger log = LoggerFactory.getLogger(SystemMetricsService.class);
    private static final String OVERVIEW_WARNING_KEY = "statusWarning";

    private final SystemInfo si = new SystemInfo();
    private final HardwareAbstractionLayer hal = si.getHardware();
    private final OperatingSystem os = si.getOperatingSystem();
    private final CommandLogRepository commandLogRepository;
    private final UserContext userContext;
    private final long overviewCacheTtlMs;
    private final Object overviewCacheLock = new Object();
    private volatile Map<String, Object> cachedOverview;
    private volatile long cachedOverviewTimestampMs;

    @Autowired
    public SystemMetricsService(
            CommandLogRepository commandLogRepository,
            UserContext userContext,
            @Value("${app.system.status-overview-cache-ttl-ms:2000}") long overviewCacheTtlMs) {
        this.commandLogRepository = commandLogRepository;
        this.userContext = userContext;
        this.overviewCacheTtlMs = Math.max(0L, overviewCacheTtlMs);
    }

    /**
     * Gets an in-depth system profile.
     *
     * @return Map containing metrics like uptime, load, memory, temperature, disks, etc.
     */
    public Map<String, Object> getServerFullMetrics() {
        Map<String, Object> metrics = new HashMap<>();

        // 1. Uptime
        long uptimeSeconds = os.getSystemUptime();
        long days = uptimeSeconds / 86400;
        long hours = (uptimeSeconds % 86400) / 3600;
        metrics.put("uptime", String.format("%d天 %d小時", days, hours));

        // 2. CPU Info (keep it easy to interpret)
        String cpuModel = hal.getProcessor().getProcessorIdentifier().getName();
        metrics.put("cpuModel", (cpuModel == null || cpuModel.isBlank()) ? null : cpuModel);
        metrics.put("cpuCoresLogical", hal.getProcessor().getLogicalProcessorCount());
        metrics.put("cpuCoresPhysical", hal.getProcessor().getPhysicalProcessorCount());
        // CPU + Disk/Net IO usage: compute a short-interval utilization so it's more intuitive than load average.
        long[] prevTicks = hal.getProcessor().getSystemCpuLoadTicks();

        // Disk IO: sample read/write bytes across all physical disks and compute MB/s over the same interval.
        List<HWDiskStore> diskStores = hal.getDiskStores();
        long diskRead1 = 0L;
        long diskWrite1 = 0L;
        long[] perDiskRead1 = null;
        long[] perDiskWrite1 = null;
        String[] perDiskName = null;
        String[] perDiskModel = null;
        if (diskStores != null && !diskStores.isEmpty()) {
            perDiskRead1 = new long[diskStores.size()];
            perDiskWrite1 = new long[diskStores.size()];
            perDiskName = new String[diskStores.size()];
            perDiskModel = new String[diskStores.size()];
            for (int i = 0; i < diskStores.size(); i++) {
                HWDiskStore s = diskStores.get(i);
                try {
                    s.updateAttributes();
                } catch (Exception ignored) {
                    // Best-effort: some environments may deny reading disk stats.
                }
                perDiskRead1[i] = s.getReadBytes();
                perDiskWrite1[i] = s.getWriteBytes();
                perDiskName[i] = s.getName();
                perDiskModel[i] = s.getModel();
                diskRead1 += perDiskRead1[i];
                diskWrite1 += perDiskWrite1[i];
            }
        }

        // Network IO: sample per-interface bytes and compute MB/s over the same interval.
        List<NetworkIF> nifs = hal.getNetworkIFs();
        long[] nifRecv1 = null;
        long[] nifSent1 = null;
        if (nifs != null && !nifs.isEmpty()) {
            nifRecv1 = new long[nifs.size()];
            nifSent1 = new long[nifs.size()];
            for (int i = 0; i < nifs.size(); i++) {
                NetworkIF nif = nifs.get(i);
                try {
                    nif.updateAttributes();
                } catch (Exception ex) {
                    log.debug("Unable to refresh network interface stats for {} (initial sample): {}", nif.getName(), ex.getMessage());
                }
                nifRecv1[i] = nif.getBytesRecv();
                nifSent1[i] = nif.getBytesSent();
            }
        }

        long startNs = System.nanoTime();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long elapsedNs = Math.max(1L, System.nanoTime() - startNs);
        double seconds = elapsedNs / 1e9;

        double cpuUsagePct = 100d * hal.getProcessor().getSystemCpuLoadBetweenTicks(prevTicks);
        if (!Double.isNaN(cpuUsagePct) && cpuUsagePct >= 0) {
            metrics.put("cpuUsagePercent", String.format("%.1f%%", cpuUsagePct));
        }

        if (diskStores != null && !diskStores.isEmpty()) {
            long diskRead2 = 0L;
            long diskWrite2 = 0L;
            List<Map<String, String>> perDisk = new ArrayList<>();
            for (int i = 0; i < diskStores.size(); i++) {
                HWDiskStore s = diskStores.get(i);
                try {
                    s.updateAttributes();
                } catch (Exception ex) {
                    log.debug("Unable to refresh disk stats for {} (second sample): {}", s.getName(), ex.getMessage());
                }

                long r2 = s.getReadBytes();
                long w2 = s.getWriteBytes();
                diskRead2 += r2;
                diskWrite2 += w2;

                long r1 = (perDiskRead1 != null && i < perDiskRead1.length) ? perDiskRead1[i] : 0L;
                long w1 = (perDiskWrite1 != null && i < perDiskWrite1.length) ? perDiskWrite1[i] : 0L;
                long dRead = Math.max(0L, r2 - r1);
                long dWrite = Math.max(0L, w2 - w1);

                double readMBps = (seconds > 0d) ? (dRead / 1e6 / seconds) : 0d;
                double writeMBps = (seconds > 0d) ? (dWrite / 1e6 / seconds) : 0d;

                String name = (perDiskName != null && i < perDiskName.length && perDiskName[i] != null) ? perDiskName[i] : s.getName();
                String model = (perDiskModel != null && i < perDiskModel.length && perDiskModel[i] != null) ? perDiskModel[i] : s.getModel();
                Map<String, String> row = new HashMap<>();
                row.put("disk", (name == null || name.isBlank()) ? "-" : name.trim());
                row.put("model", (model == null || model.isBlank()) ? "-" : model.trim());
                row.put("readMBps", String.format("%.1f MB/s", readMBps));
                row.put("writeMBps", String.format("%.1f MB/s", writeMBps));
                perDisk.add(row);
            }

            metrics.put("diskIoByDisk", perDisk);
        }

        if (nifs != null && !nifs.isEmpty()) {
            List<Map<String, String>> perIf = new ArrayList<>();
            for (int i = 0; i < nifs.size(); i++) {
                NetworkIF nif = nifs.get(i);
                try {
                    nif.updateAttributes();
                } catch (Exception ex) {
                    log.debug("Unable to refresh network interface stats for {} (second sample): {}", nif.getName(), ex.getMessage());
                }

                String name = (nif.getName() == null || nif.getName().isBlank()) ? "-" : nif.getName().trim();
                if ("lo".equals(name)) continue;

                long r1 = (nifRecv1 != null && i < nifRecv1.length) ? nifRecv1[i] : 0L;
                long s1 = (nifSent1 != null && i < nifSent1.length) ? nifSent1[i] : 0L;
                long r2 = nif.getBytesRecv();
                long s2 = nif.getBytesSent();
                long dRecv = Math.max(0L, r2 - r1);
                long dSent = Math.max(0L, s2 - s1);

                double rxMBps = (seconds > 0d) ? (dRecv / 1e6 / seconds) : 0d;
                double txMBps = (seconds > 0d) ? (dSent / 1e6 / seconds) : 0d;

                String ipv4 = (nif.getIPv4addr() != null && nif.getIPv4addr().length > 0)
                        ? String.join(", ", nif.getIPv4addr())
                        : "-";
                long speedBps = nif.getSpeed();
                String speed = (speedBps > 0) ? String.format("%.0f Mbps", speedBps / 1e6) : "-";

                Map<String, String> row = new HashMap<>();
                row.put("interface", name);
                row.put("ipv4", ipv4);
                row.put("speed", speed);
                row.put("rx", String.format("%.1f MB/s", rxMBps));
                row.put("tx", String.format("%.1f MB/s", txMBps));
                perIf.add(row);
            }
            perIf.sort(java.util.Comparator.comparing(m -> m.getOrDefault("interface", "")));
            metrics.put("networkIfs", perIf);
        }

        // 3. Memory (Used / Total, GB)
        double availableGb = hal.getMemory().getAvailable() / 1e9;
        double totalGb = hal.getMemory().getTotal() / 1e9;
        double usedGb = Math.max(0d, totalGb - availableGb);
        double memUsagePct = (totalGb > 0d) ? (100d * usedGb / totalGb) : Double.NaN;
        // Keep the old key for backward compatibility, but /status uses usedMemoryGB now.
        metrics.put("availableMemoryGB", String.format("%.2f GB / %.2f GB", availableGb, totalGb));
        metrics.put("usedMemoryGB", String.format("%.2f GB / %.2f GB", usedGb, totalGb));
        if (!Double.isNaN(memUsagePct) && memUsagePct >= 0) {
            metrics.put("memoryUsagePercent", String.format("%.1f%%", memUsagePct));
        }
        if (hal.getMemory().getVirtualMemory() != null) {
            double swapUsedGb = hal.getMemory().getVirtualMemory().getSwapUsed() / 1e9;
            double swapTotalGb = hal.getMemory().getVirtualMemory().getSwapTotal() / 1e9;
            metrics.put("swapGB", String.format("%.2f GB / %.2f GB", swapUsedGb, swapTotalGb));
        }

        // 4. CPU Temperature
        double cpuTemp = hal.getSensors().getCpuTemperature();
        if (!Double.isNaN(cpuTemp) && cpuTemp > 0d) {
            metrics.put("cpuTemp", String.format("%.1f°C", cpuTemp));
        }

        // 5. OS / Host
        metrics.put("hostname", os.getNetworkParams().getHostName());
        metrics.put("os", os.toString());
        metrics.put("processCount", os.getProcessCount());
        metrics.put("threadCount", os.getThreadCount());

        // 6. Disk Usage (OSHI) + Inode Usage per mount point
        // Include all "real" mount points by default. OSHI may report pseudo file systems
        // (procfs/sysfs/tmpfs/overlay/squashfs). Filter those out to keep output useful.
        List<OSFileStore> fileStores = os.getFileSystem().getFileStores().stream()
                .filter(fs -> fs.getMount() != null && fs.getMount().startsWith("/"))
                .filter(fs -> fs.getTotalSpace() > 0)
                .filter(fs -> {
                    String mount = fs.getMount();
                    // Pseudo/system mounts
                    return !(mount.startsWith("/proc")
                            || mount.startsWith("/sys")
                            || mount.startsWith("/run")
                            || mount.startsWith("/dev"));
                })
                .filter(fs -> {
                    String type = fs.getType() != null ? fs.getType().toLowerCase() : "";
                    String mount = fs.getMount();
                    // Keep "/" even if it's overlay (common on some containerized setups).
                    if ("/".equals(mount)) return true;
                    return !(type.equals("tmpfs")
                            || type.equals("devtmpfs")
                            || type.equals("squashfs")
                            || type.equals("overlay"));
                })
                .sorted(java.util.Comparator.comparing(OSFileStore::getMount))
                .collect(Collectors.toList());

        List<String> disks = fileStores.stream()
                .map(fs -> String.format("%s: 總共 %.1f GB / 剩餘 %.1f GB (%.1f%%)",
                        fs.getMount(),
                        fs.getTotalSpace() / 1_073_741_824.0,
                        fs.getUsableSpace() / 1_073_741_824.0,
                        100d * (fs.getTotalSpace() - fs.getUsableSpace()) / fs.getTotalSpace()))
                .collect(Collectors.toList());
        metrics.put("disks", disks);

        List<Map<String, String>> diskMounts = new ArrayList<>();
        for (OSFileStore fs : fileStores) {
            Map<String, String> row = new HashMap<>();
            String mount = (fs.getMount() == null || fs.getMount().isBlank()) ? "-" : fs.getMount().trim();
            double totalGbFs = fs.getTotalSpace() / 1_073_741_824.0;
            double freeGbFs = fs.getUsableSpace() / 1_073_741_824.0;
            double usePct = (fs.getTotalSpace() > 0) ? (100d * (fs.getTotalSpace() - fs.getUsableSpace()) / fs.getTotalSpace()) : Double.NaN;

            row.put("mount", mount);
            row.put("totalGB", String.format("%.1f", totalGbFs));
            row.put("freeGB", String.format("%.1f", freeGbFs));
            row.put("usePct", (!Double.isNaN(usePct) && usePct >= 0d) ? String.format("%.1f%%", usePct) : "-");

            long totalInodes = fs.getTotalInodes();
            long freeInodes = fs.getFreeInodes();
            if (totalInodes > 0 && freeInodes >= 0) {
                double inodeUsePct = 100d * (totalInodes - freeInodes) / totalInodes;
                row.put("inodeUsePct", String.format("%.1f%%", inodeUsePct));
            } else {
                row.put("inodeUsePct", "-");
            }

            diskMounts.add(row);
        }
        if (!diskMounts.isEmpty()) metrics.put("diskMounts", diskMounts);

        return metrics;
    }

    /**
     * Gets recent system logs.
     *
     * @return Last 50 lines of system logs
     */
    public String getRecentLogs() {
        String result;
        boolean success = true;
        try {
            Process process = new ProcessBuilder("journalctl", "-n", "50", "--no-pager")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                result = reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            result = "[ERROR] 無法讀取系統日誌: " + e.getMessage();
            success = false;
        }
        saveAuditLog("journalctl -n 50 (recent logs)", result, success);
        return result;
    }

    /**
     * Gets system overview (integrates all basic metrics).
     *
     * @return Map containing all system metrics
     */
    public Map<String, Object> getSystemOverview() {
        if (overviewCacheTtlMs <= 0L) {
            return buildSystemOverviewSafely();
        }

        long now = System.currentTimeMillis();
        Map<String, Object> cached = cachedOverview;
        if (cached != null && now - cachedOverviewTimestampMs <= overviewCacheTtlMs) {
            return new HashMap<>(cached);
        }

        synchronized (overviewCacheLock) {
            now = System.currentTimeMillis();
            cached = cachedOverview;
            if (cached != null && now - cachedOverviewTimestampMs <= overviewCacheTtlMs) {
                return new HashMap<>(cached);
            }

            Map<String, Object> overview = buildSystemOverviewSafely();
            cachedOverview = overview;
            cachedOverviewTimestampMs = System.currentTimeMillis();
            return new HashMap<>(overview);
        }
    }

    private Map<String, Object> buildSystemOverviewSafely() {
        Map<String, Object> overview = new HashMap<>();
        try {
            overview.putAll(getServerFullMetrics());
        } catch (Exception ex) {
            log.error("Failed to collect full system metrics: {}", ex.getMessage(), ex);
            overview.put(OVERVIEW_WARNING_KEY, "部分系統監控資料暫時無法讀取");
        }
        overview.put("timestamp", Instant.now().toString());
        return overview;
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
            AuditLogPersistenceService.persist(commandLogRepository, cmdLog, "SystemMetricsService");
        } catch (Exception ex) {
            log.error("[Audit] Failed to save system metrics log: {}", ex.getMessage());
        }
    }
}
