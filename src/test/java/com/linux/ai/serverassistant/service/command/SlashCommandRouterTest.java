package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.service.docker.DockerSnapshotService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.system.GpuStatusService;
import com.linux.ai.serverassistant.service.system.PortStatusService;
import com.linux.ai.serverassistant.service.system.ProcessMonitorService;
import com.linux.ai.serverassistant.service.system.SystemMetricsService;
import com.linux.ai.serverassistant.service.user.UserCommandConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlashCommandRouterTest {

    private SlashCommandRouter router;
    private GpuStatusService gpuStatusService;
    private AdminAuthorizationService adminAuthorizationService;
    private NaturalLanguageQueryMatcher naturalLanguageQueryMatcher;

    @BeforeEach
    void setUp() {
        UserManagementRouter userManagementRouter = mock(UserManagementRouter.class);
        SystemMetricsService systemMetricsService = mock(SystemMetricsService.class);
        gpuStatusService = mock(GpuStatusService.class);
        ProcessMonitorService processMonitorService = mock(ProcessMonitorService.class);
        DockerSnapshotService dockerSnapshotService = mock(DockerSnapshotService.class);
        PortStatusService portStatusService = mock(PortStatusService.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);
        SlashCommandRiskyOperationService riskyOperationService = mock(SlashCommandRiskyOperationService.class);
        naturalLanguageQueryMatcher = mock(NaturalLanguageQueryMatcher.class);

        router = new SlashCommandRouter(
                userManagementRouter,
                systemMetricsService,
                gpuStatusService,
                processMonitorService,
                dockerSnapshotService,
                portStatusService,
                adminAuthorizationService,
                riskyOperationService,
                naturalLanguageQueryMatcher
        );
    }

    @Test
    void route_nonSlash_shouldDelegateToNaturalLanguageMatcher() {
        when(naturalLanguageQueryMatcher.match("查詢 GPU", "alice"))
                .thenReturn(Optional.of(new DeterministicRouter.AssistantText("matched")));

        Optional<DeterministicRouter.Route> route =
                router.route(new DeterministicRouter.Context("查詢 GPU", "c1", "alice"));

        assertTrue(route.isPresent());
        assertEquals("matched", ((DeterministicRouter.AssistantText) route.get()).text());
    }

    @Test
    void route_gpu_shouldReturnAssistantText() {
        when(gpuStatusService.getGpuStatus()).thenReturn("GPU OK");

        Optional<DeterministicRouter.Route> route =
                router.route(new DeterministicRouter.Context("/gpu", "c1", "alice"));

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
    }

    @Test
    void route_users_nonAdmin_shouldReturnPermissionText() {
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);

        Optional<DeterministicRouter.Route> route =
                router.route(new DeterministicRouter.Context("/users", "c1", "alice"));

        assertTrue(route.isPresent());
        String text = ((DeterministicRouter.AssistantText) route.get()).text();
        assertTrue(text.contains("僅限管理員"));
    }

    @Test
    void route_users_admin_shouldReturnLinuxCommandRoute() {
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(true);

        Optional<DeterministicRouter.Route> route =
                router.route(new DeterministicRouter.Context("/users", "c1", "alice"));

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.LinuxCommand);
        DeterministicRouter.LinuxCommand command = (DeterministicRouter.LinuxCommand) route.get();
        assertEquals(UserCommandConstants.LIST_LOGIN_USERS_COMMAND, command.command());
        assertTrue(command.responsePrefix().contains("系統使用者"));
    }

    @Test
    void route_help_shouldIncludeExclamationCommandUsage() {
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);

        Optional<DeterministicRouter.Route> route =
                router.route(new DeterministicRouter.Context("/help", "c1", "alice"));

        assertTrue(route.isPresent());
        assertTrue(route.get() instanceof DeterministicRouter.AssistantText);
        String text = ((DeterministicRouter.AssistantText) route.get()).text();
        assertTrue(text.contains("`!<command>`"));
        assertTrue(text.contains("`!df -h`"));
    }
}
