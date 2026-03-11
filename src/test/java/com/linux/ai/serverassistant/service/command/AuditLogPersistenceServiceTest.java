package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class AuditLogPersistenceServiceTest {

    @Test
    void persist_shouldRetryWhenInitialSaveFails() {
        CommandLogRepository repository = mock(CommandLogRepository.class);
        AtomicInteger saveCount = new AtomicInteger(0);
        doAnswer(invocation -> {
            if (saveCount.getAndIncrement() == 0) {
                throw new RuntimeException("db unavailable");
            }
            return invocation.getArgument(0);
        }).when(repository).save(any(CommandLog.class));

        CommandLog cmdLog = new CommandLog();
        cmdLog.setUsername("alice");
        cmdLog.setCommand("echo hello");
        cmdLog.setOutput("ok");
        cmdLog.setSuccess(true);

        AuditLogPersistenceService.persist(repository, cmdLog, "AuditLogPersistenceServiceTest");

        verify(repository, timeout(3000).times(2)).save(any(CommandLog.class));
    }
}
