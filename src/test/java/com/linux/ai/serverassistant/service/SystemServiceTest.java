package com.linux.ai.serverassistant.service;

import com.linux.ai.serverassistant.service.system.NetworkMonitorService;
import com.linux.ai.serverassistant.service.system.ProcessMonitorService;
import com.linux.ai.serverassistant.service.system.SystemMetricsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemServiceTest {

    private SystemMetricsService metricsService;
    private ProcessMonitorService processMonitorService;
    private NetworkMonitorService networkMonitorService;
    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        metricsService = mock(SystemMetricsService.class);
        processMonitorService = mock(ProcessMonitorService.class);
        networkMonitorService = mock(NetworkMonitorService.class);
        executorService = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void getSystemStatus_shouldAggregateAllSources() {
        when(metricsService.getServerFullMetrics()).thenReturn(Map.of("cpu", "21%"));
        when(networkMonitorService.getNetworkStatus()).thenReturn(List.of(Map.of("iface", "eth0")));
        when(processMonitorService.getTopProcesses()).thenReturn(List.of("java"));

        SystemService service = new SystemService(metricsService, processMonitorService, networkMonitorService, executorService);
        Map<String, Object> status = service.getSystemStatus();

        assertEquals("21%", status.get("cpu"));
        assertEquals(List.of(Map.of("iface", "eth0")), status.get("network"));
        assertEquals(List.of("java"), status.get("topProcesses"));
        assertTrue(status.containsKey("timestamp"));
    }

    @Test
    void getSystemStatus_whenExecutorRejected_shouldMarkDegraded() {
        ExecutorService rejectingExecutor = new RejectingExecutorService();
        SystemService service = new SystemService(metricsService, processMonitorService, networkMonitorService, rejectingExecutor);

        Map<String, Object> status = service.getSystemStatus();

        assertEquals(Boolean.TRUE, status.get("degraded"));
        @SuppressWarnings("unchecked")
        List<String> degradedTasks = (List<String>) status.get("degradedTasks");
        assertTrue(degradedTasks.contains("metrics"));
        assertTrue(degradedTasks.contains("network"));
        assertTrue(degradedTasks.contains("topProcesses"));
        rejectingExecutor.shutdownNow();
    }

    private static final class RejectingExecutorService extends AbstractExecutorService {
        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("rejected");
        }
    }
}
