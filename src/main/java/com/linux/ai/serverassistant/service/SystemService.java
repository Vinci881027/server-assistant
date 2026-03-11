package com.linux.ai.serverassistant.service;

import com.linux.ai.serverassistant.service.system.NetworkMonitorService;
import com.linux.ai.serverassistant.service.system.ProcessMonitorService;
import com.linux.ai.serverassistant.service.system.SystemMetricsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * Aggregates system status from multiple monitoring services in parallel.
 * All other operations are handled directly by their respective services.
 */
@Service
public class SystemService {

    private final SystemMetricsService systemMetricsService;
    private final ProcessMonitorService processMonitorService;
    private final NetworkMonitorService networkMonitorService;
    private final ExecutorService systemStatusExecutor;

    @Autowired
    public SystemService(
            SystemMetricsService systemMetricsService,
            ProcessMonitorService processMonitorService,
            NetworkMonitorService networkMonitorService,
            @Qualifier("systemStatusExecutor") ExecutorService systemStatusExecutor
    ) {
        this.systemMetricsService = systemMetricsService;
        this.processMonitorService = processMonitorService;
        this.networkMonitorService = networkMonitorService;
        this.systemStatusExecutor = systemStatusExecutor;
    }

    /**
     * Integrates all monitoring metrics, providing a one-click system status scan.
     *
     * @return Map containing system status, network, processes, etc.
     */
    public Map<String, Object> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        List<String> degradedTasks = new ArrayList<>();

        CompletableFuture<Map<String, Object>> metricsFuture =
                submitStatusTask(
                        systemMetricsService::getServerFullMetrics,
                        Map.of(),
                        "metrics",
                        degradedTasks);
        CompletableFuture<List<Map<String, String>>> networkFuture =
                submitStatusTask(
                        networkMonitorService::getNetworkStatus,
                        List.of(),
                        "network",
                        degradedTasks);
        CompletableFuture<List<String>> topProcessesFuture =
                submitStatusTask(
                        processMonitorService::getTopProcesses,
                        List.of(),
                        "topProcesses",
                        degradedTasks);

        CompletableFuture.allOf(metricsFuture, networkFuture, topProcessesFuture).join();

        // 1. Basic metrics (Uptime, Load, Memory, Temp, Disk)
        status.putAll(metricsFuture.join());

        // 2. Network status
        status.put("network", networkFuture.join());

        // 3. High-load processes
        status.put("topProcesses", topProcessesFuture.join());

        // 4. Timestamp
        status.put("timestamp", Instant.now().toString());

        if (!degradedTasks.isEmpty()) {
            status.put("degraded", true);
            status.put("degradedTasks", List.copyOf(degradedTasks));
            status.put("degradedReason", "systemStatusExecutor saturated");
        }

        return status;
    }

    private <T> CompletableFuture<T> submitStatusTask(
            Supplier<T> task,
            T fallbackValue,
            String taskName,
            List<String> degradedTasks) {
        try {
            return CompletableFuture.supplyAsync(task, systemStatusExecutor);
        } catch (RejectedExecutionException ex) {
            degradedTasks.add(taskName);
            return CompletableFuture.completedFuture(fallbackValue);
        }
    }

}
