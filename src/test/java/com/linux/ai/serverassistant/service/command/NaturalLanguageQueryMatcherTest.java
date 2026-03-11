package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.docker.DockerSnapshotService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.system.GpuStatusService;
import com.linux.ai.serverassistant.service.system.PortStatusService;
import com.linux.ai.serverassistant.service.system.ProcessMonitorService;
import com.linux.ai.serverassistant.service.system.SystemMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NaturalLanguageQueryMatcherTest {

    private NaturalLanguageQueryMatcher matcher;
    private DockerSnapshotService dockerSnapshotService;
    private ProcessMonitorService processMonitorService;
    private AdminAuthorizationService adminAuthorizationService;

    @BeforeEach
    void setUp() {
        SystemMetricsService systemMetricsService = mock(SystemMetricsService.class);
        GpuStatusService gpuStatusService = mock(GpuStatusService.class);
        processMonitorService = mock(ProcessMonitorService.class);
        dockerSnapshotService = mock(DockerSnapshotService.class);
        PortStatusService portStatusService = mock(PortStatusService.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);

        matcher = new NaturalLanguageQueryMatcher(
                systemMetricsService,
                gpuStatusService,
                processMonitorService,
                dockerSnapshotService,
                portStatusService,
                adminAuthorizationService
        );

        when(dockerSnapshotService.getSnapshot()).thenReturn(Map.of(
                "containers", List.of(),
                "stats", List.of()
        ));
        when(processMonitorService.getTopCpuProcessInfos(anyInt())).thenReturn(List.of(Map.of(
                "pid", 1001,
                "user", "root",
                "cpuPercent", "85.0%",
                "memGB", "1.20",
                "cmd", "java -jar app.jar"
        )));
        when(processMonitorService.getTopMemoryProcessInfos(anyInt())).thenReturn(List.of(Map.of(
                "pid", 2002,
                "user", "service",
                "cpuPercent", "12.0%",
                "memGB", "3.50",
                "cmd", "python worker.py"
        )));
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);
        when(adminAuthorizationService.isAdmin("admin")).thenReturn(true);
    }

    @Test
    void match_dockerHasInRunning_shouldRouteToDockerSnapshot() {
        Optional<DeterministicRouter.Route> route = matcher.match("docker 有在跑嗎？", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
        String text = ((DeterministicRouter.AssistantText) route.get()).text();
        assertTrue(text.contains("Docker 狀態"));
    }

    @Test
    void match_containerRunningQuestion_shouldRouteToDockerSnapshot() {
        Optional<DeterministicRouter.Route> route = matcher.match("哪些 container 在運行", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
    }

    @Test
    void match_dockerUsingPaoLe_shouldRouteToDockerSnapshot() {
        Optional<DeterministicRouter.Route> route = matcher.match("docker 跑了嗎", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
    }

    @Test
    void match_dockerUsingYunZuo_shouldRouteToDockerSnapshot() {
        Optional<DeterministicRouter.Route> route = matcher.match("docker 現在運作嗎", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
    }

    @Test
    void match_dockerHowIsIt_shouldFallthroughToAi() {
        Optional<DeterministicRouter.Route> route = matcher.match("docker 怎麼了", "alice");

        assertFalse(route.isPresent());
    }

    @Test
    void match_userStandalone_shouldFallthroughToAi() {
        Optional<DeterministicRouter.Route> route = matcher.match("user", "alice");

        assertFalse(route.isPresent());
    }

    @Test
    void match_systemUser_shouldFallthroughToAi() {
        Optional<DeterministicRouter.Route> route = matcher.match("system user", "alice");

        assertFalse(route.isPresent());
    }

    @Test
    void match_systemUsersPhrase_shouldRouteToUsers() {
        Optional<DeterministicRouter.Route> route = matcher.match("系統使用者", "admin");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.LinuxCommand);
    }

    @Test
    void match_systemUserStatus_shouldRouteToSystemStatus() {
        Optional<DeterministicRouter.Route> route = matcher.match("system user status", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
        String text = ((DeterministicRouter.AssistantText) route.get()).text();
        assertTrue(text.contains("系統狀態"));
    }

    @Test
    void match_topCpuHighest_shouldRouteToTopCpu() {
        Optional<DeterministicRouter.Route> route = matcher.match("cpu 使用率最高的進程", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
        String text = ((DeterministicRouter.AssistantText) route.get()).text();
        assertTrue(text.contains("Top CPU"));
        verify(processMonitorService).getTopCpuProcessInfos(5);
    }

    @Test
    void match_topWithCpuAndMem_shouldReturnTopUsageHint() {
        Optional<DeterministicRouter.Route> route = matcher.match("top cpu 記憶體", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
        String text = ((DeterministicRouter.AssistantText) route.get()).text();
        assertTrue(text.contains("/top cpu [limit]"));
        assertTrue(text.contains("/top mem [limit]"));
    }

    @Test
    void match_loadHighReversedWordOrder_shouldReturnTopUsageHint() {
        Optional<DeterministicRouter.Route> route = matcher.match("負載高", "alice");

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
        String text = ((DeterministicRouter.AssistantText) route.get()).text();
        assertTrue(text.contains("/top cpu [limit]"));
    }
}
