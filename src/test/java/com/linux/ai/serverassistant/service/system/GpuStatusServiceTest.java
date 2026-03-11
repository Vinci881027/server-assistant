package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.entity.CommandType;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.command.CommandAuditService;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GpuStatusServiceTest {

    @Test
    void getGpuStatus_shouldPersistAuditWithReturnedOutput() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        UserContext userContext = mock(UserContext.class);
        when(userContext.resolveUsernameOrAnonymous()).thenReturn("anonymous");
        GpuStatusService service = new GpuStatusService(repo, userContext);

        String status = service.getGpuStatus();

        ArgumentCaptor<CommandLog> captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(repo).save(captor.capture());
        CommandLog saved = captor.getValue();

        boolean success = status != null
                && !status.contains("[ERROR]")
                && !status.contains(CommandMarkers.SECURITY_VIOLATION);

        assertEquals("system", saved.getUsername());
        assertEquals("nvidia-smi (deterministic)", saved.getCommand());
        assertEquals(CommandType.READ, saved.getCommandType());
        assertEquals(CommandAuditService.truncateOutput(status), saved.getOutput());
        assertEquals(success, saved.isSuccess());
    }

    @Test
    void extractNvidiaHeaderLine_shouldReturnMatchedHeaderLine() throws Exception {
        GpuStatusService service = new GpuStatusService(mock(CommandLogRepository.class), mock(UserContext.class));
        String sample = """
                +-----------------------------------------------------------------------------+
                | NVIDIA-SMI 535.129.03    Driver Version: 535.129.03    CUDA Version: 12.2 |
                +-----------------------------------------------------------------------------+
                """;

        String header = (String) invokePrivate(service, "extractNvidiaHeaderLine", new Class[]{String.class}, sample);

        assertEquals("| NVIDIA-SMI 535.129.03    Driver Version: 535.129.03    CUDA Version: 12.2 |", header);
    }

    @Test
    void extractNvidiaHeaderLine_whenNoHeader_shouldReturnNull() throws Exception {
        GpuStatusService service = new GpuStatusService(mock(CommandLogRepository.class), mock(UserContext.class));
        String sample = "GPU information without driver/cuda line";

        String header = (String) invokePrivate(service, "extractNvidiaHeaderLine", new Class[]{String.class}, sample);

        assertNull(header);
    }

    @Test
    void extractNvidiaProcessesBlock_shouldReturnSectionFromProcessesMarker() throws Exception {
        GpuStatusService service = new GpuStatusService(mock(CommandLogRepository.class), mock(UserContext.class));
        String sample = """
                preface
                | Processes:                                                                  |
                |  GPU   PID   Type   Process name                            GPU Memory      |
                |    0  1234      C   python                                          512MiB |
                +-----------------------------------------------------------------------------+
                """;

        String block = (String) invokePrivate(service, "extractNvidiaProcessesBlock", new Class[]{String.class}, sample);

        assertTrue(block.startsWith("| Processes:"));
        assertTrue(block.contains("python"));
        assertTrue(block.contains("+-----------------------------------------------------------------------------+"));
    }

    @Test
    void extractNvidiaProcessesBlock_whenMarkerMissing_shouldReturnNull() throws Exception {
        GpuStatusService service = new GpuStatusService(mock(CommandLogRepository.class), mock(UserContext.class));

        String block = (String) invokePrivate(service, "extractNvidiaProcessesBlock", new Class[]{String.class}, "no process marker");

        assertNull(block);
    }

    @Test
    void limitErrorDetail_shouldTruncateLongText() throws Exception {
        GpuStatusService service = new GpuStatusService(mock(CommandLogRepository.class), mock(UserContext.class));

        String limited = (String) invokePrivate(
                service,
                "limitErrorDetail",
                new Class[]{String.class, int.class},
                "0123456789",
                4);

        assertEquals("0123...", limited);
    }

    @Test
    void safe_shouldTrimAndRemoveLineBreaks() throws Exception {
        GpuStatusService service = new GpuStatusService(mock(CommandLogRepository.class), mock(UserContext.class));

        String safe = (String) invokePrivate(service, "safe", new Class[]{String.class}, "  a\nb  ");

        assertEquals("a b", safe);
    }

    @Test
    void joinCommandResult_whenFutureFails_shouldReturnErrorResult() throws Exception {
        GpuStatusService service = new GpuStatusService(mock(CommandLogRepository.class), mock(UserContext.class));
        CompletableFuture<?> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("boom"));

        Object commandResult = invokePrivate(
                service,
                "joinCommandResult",
                new Class[]{CompletableFuture.class, String.class},
                failed,
                "nvidia-smi");

        Method successAccessor = commandResult.getClass().getDeclaredMethod("success");
        Method errorAccessor = commandResult.getClass().getDeclaredMethod("errorMessage");
        successAccessor.setAccessible(true);
        errorAccessor.setAccessible(true);

        assertFalse((boolean) successAccessor.invoke(commandResult));
        assertTrue(((String) errorAccessor.invoke(commandResult)).contains("GPU 查詢指令執行失敗: nvidia-smi"));
    }

    private Object invokePrivate(Object target, String methodName, Class<?>[] argTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName, argTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }
}
