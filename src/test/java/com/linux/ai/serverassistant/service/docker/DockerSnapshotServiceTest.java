package com.linux.ai.serverassistant.service.docker;

import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DockerSnapshotServiceTest {

    private DockerSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new DockerSnapshotService(mock(CommandLogRepository.class), new UserContext());
    }

    @Test
    void parsePipedRows_daemonError_shouldReturnEmpty() throws Exception {
        Method m = DockerSnapshotService.class.getDeclaredMethod(
                "parsePipedRows", String.class, List.class, int.class);
        m.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) m.invoke(
                service,
                "Cannot connect to the Docker daemon at unix:///var/run/docker.sock. Is the docker daemon running?",
                List.of("name", "image", "status", "ports"),
                50);

        assertTrue(rows.isEmpty());
    }

    @Test
    void cleanSingleLineOrDash_daemonError_shouldReturnDash() throws Exception {
        Method m = DockerSnapshotService.class.getDeclaredMethod("cleanSingleLineOrDash", String.class);
        m.setAccessible(true);

        String out = (String) m.invoke(
                service,
                "Error response from daemon: permission denied while trying to connect");

        assertEquals("-", out);
    }

    @Test
    void firstErrorLine_daemonError_shouldReturnFirstLine() throws Exception {
        Method m = DockerSnapshotService.class.getDeclaredMethod("firstErrorLine", String[].class);
        m.setAccessible(true);

        String out = (String) m.invoke(
                service,
                (Object) new String[]{
                        "1.2.3",
                        "Cannot connect to the Docker daemon at unix:///var/run/docker.sock.\nIs the docker daemon running?"
                });

        assertEquals("Cannot connect to the Docker daemon at unix:///var/run/docker.sock.", out);
    }
}
