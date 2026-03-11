package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.entity.CommandType;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.CommandAuditService;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemMetricsServiceTest {

    @Test
    void getSystemOverview_withPositiveTtl_shouldReuseCache() {
        TestableSystemMetricsService svc = new TestableSystemMetricsService(2_000L);

        Map<String, Object> first = svc.getSystemOverview();
        Map<String, Object> second = svc.getSystemOverview();

        assertEquals(1, svc.metricCalls.get());
        assertEquals("1天", first.get("uptime"));
        assertEquals("1天", second.get("uptime"));
    }

    @Test
    void getSystemOverview_withZeroTtl_shouldBypassCache() {
        TestableSystemMetricsService svc = new TestableSystemMetricsService(0L);

        svc.getSystemOverview();
        svc.getSystemOverview();

        assertEquals(2, svc.metricCalls.get());
    }

    @Test
    void getSystemOverview_whenMetricCollectionFails_shouldReturnWarning() {
        TestableSystemMetricsService svc = new TestableSystemMetricsService(2_000L);
        svc.fail = true;

        Map<String, Object> overview = svc.getSystemOverview();

        assertTrue(overview.containsKey("timestamp"));
        assertEquals("部分系統監控資料暫時無法讀取", overview.get("statusWarning"));
    }

    @Test
    void getSystemOverview_withPositiveTtl_shouldReturnDefensiveCopy() {
        TestableSystemMetricsService svc = new TestableSystemMetricsService(2_000L);

        Map<String, Object> first = svc.getSystemOverview();
        first.put("mutated", true);

        Map<String, Object> second = svc.getSystemOverview();

        assertEquals(1, svc.metricCalls.get());
        assertFalse(second.containsKey("mutated"));
    }

    @Test
    void getRecentLogs_withAnonymousUser_shouldPersistAuditAsSystemUser() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        UserContext userContext = mock(UserContext.class);
        when(userContext.resolveUsernameOrAnonymous()).thenReturn("anonymous");

        SystemMetricsService svc = new SystemMetricsService(repo, userContext, 0L);
        String logs = svc.getRecentLogs();

        ArgumentCaptor<CommandLog> captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(repo).save(captor.capture());
        CommandLog saved = captor.getValue();

        assertEquals("system", saved.getUsername());
        assertEquals("journalctl -n 50 (recent logs)", saved.getCommand());
        assertEquals(CommandAuditService.truncateOutput(logs), saved.getOutput());
        assertEquals(CommandType.READ, saved.getCommandType());
    }

    @Test
    void getRecentLogs_withResolvedUser_shouldPersistThatUsername() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        UserContext userContext = mock(UserContext.class);
        when(userContext.resolveUsernameOrAnonymous()).thenReturn("alice");

        SystemMetricsService svc = new SystemMetricsService(repo, userContext, 0L);
        svc.getRecentLogs();

        ArgumentCaptor<CommandLog> captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(repo).save(captor.capture());
        assertEquals("alice", captor.getValue().getUsername());
    }

    @Test
    void getServerFullMetrics_shouldReturnCoreContractFields() {
        SystemMetricsService svc = new SystemMetricsService(mock(CommandLogRepository.class), mock(UserContext.class), 0L);

        Map<String, Object> metrics = svc.getServerFullMetrics();

        assertNotNull(metrics);
        assertTrue(metrics.containsKey("uptime"));
        assertTrue(metrics.containsKey("cpuCoresLogical"));
        assertTrue(metrics.containsKey("cpuCoresPhysical"));
        assertTrue(metrics.containsKey("usedMemoryGB"));
        assertTrue(metrics.containsKey("disks"));
        assertTrue(metrics.containsKey("hostname"));
        assertTrue(metrics.containsKey("os"));
    }

    private static final class TestableSystemMetricsService extends SystemMetricsService {
        private final AtomicInteger metricCalls = new AtomicInteger();
        private volatile boolean fail;

        private TestableSystemMetricsService(long ttlMs) {
            super(mock(CommandLogRepository.class), new UserContext(), ttlMs);
        }

        @Override
        public Map<String, Object> getServerFullMetrics() {
            metricCalls.incrementAndGet();
            if (fail) throw new RuntimeException("boom");
            return new HashMap<>(Map.of("uptime", "1天"));
        }
    }
}
