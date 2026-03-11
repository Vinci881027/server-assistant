package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class PortStatusServiceTest {

    @Test
    void getListeningPorts_whenSsFails_shouldFallbackToNetstat() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        PortStatusService service = new PortStatusService(repo, new UserContext());

        Map<String, Object> out = service.getListeningPorts(
                cmd -> true,
                cmd -> {
                    if ("ss".equals(cmd.get(0))) return "[ERROR] port failed: ss boom";
                    return """
                            tcp        0      0 0.0.0.0:8080      0.0.0.0:*      LISTEN
                            udp        0      0 127.0.0.53:53     0.0.0.0:*
                            """;
                }
        );

        assertEquals("netstat", out.get("source"));
        assertNull(out.get("error"));
        List<Map<String, String>> rows = rows(out);
        assertEquals(2, rows.size());
        assertEquals("53", rows.get(0).get("port"));
        assertEquals("8080", rows.get(1).get("port"));
        assertEquals("-", rows.get(0).get("state"));
        verify(repo).save(any(CommandLog.class));
    }

    @Test
    void getListeningPorts_whenBothFail_shouldReturnCombinedErrorAndSkipAuditLog() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        PortStatusService service = new PortStatusService(repo, new UserContext());

        Map<String, Object> out = service.getListeningPorts(
                cmd -> true,
                cmd -> "[ERROR] fail-" + cmd.get(0)
        );

        assertEquals("ss,netstat", out.get("source"));
        String err = String.valueOf(out.get("error"));
        assertTrue(err.contains("ss"));
        assertTrue(err.contains("netstat"));
        assertTrue(rows(out).isEmpty());
        verify(repo, never()).save(any(CommandLog.class));
    }

    @Test
    void getListeningPorts_whenSsOutputEmpty_shouldReturnEmptyRowsWithoutError() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        PortStatusService service = new PortStatusService(repo, new UserContext());

        Map<String, Object> out = service.getListeningPorts(
                cmd -> "ss".equals(cmd),
                cmd -> ""
        );

        assertEquals("ss", out.get("source"));
        assertNull(out.get("error"));
        assertTrue(rows(out).isEmpty());

        ArgumentCaptor<CommandLog> captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(repo).save(captor.capture());
        assertNotNull(captor.getValue());
        assertEquals("ss (deterministic)", captor.getValue().getCommand());
        assertEquals("0 listening ports", captor.getValue().getOutput());
        assertTrue(captor.getValue().isSuccess());
    }

    @Test
    void getListeningPorts_whenNoCommandsAvailable_shouldReturnError() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        PortStatusService service = new PortStatusService(repo, new UserContext());

        Map<String, Object> out = service.getListeningPorts(
                cmd -> false,
                cmd -> "[ERROR] should-not-run"
        );

        assertEquals("-", out.get("source"));
        assertTrue(String.valueOf(out.get("error")).contains("找不到 `ss` 或 `netstat`"));
        assertTrue(rows(out).isEmpty());
        verify(repo, never()).save(any(CommandLog.class));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, String>> rows(Map<String, Object> out) {
        return (List<Map<String, String>>) out.get("rows");
    }
}
