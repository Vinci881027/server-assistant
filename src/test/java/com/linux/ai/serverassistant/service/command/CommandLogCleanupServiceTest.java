package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CommandLogCleanupServiceTest {

    @Test
    void cleanupExpiredLogs_shouldDeleteEntriesOlderThanRetentionDays() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        CommandLogRepository commandLogRepository = mock(CommandLogRepository.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        when(commandLogRepository.deleteByExecutionTimeBefore(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(12);
        when(chatMessageRepository.deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(24);

        CommandLogCleanupService service = new CommandLogCleanupService(commandLogRepository, chatMessageRepository, clock, 90);
        service.cleanupExpiredLogs();

        ArgumentCaptor<LocalDateTime> commandCutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(commandLogRepository).deleteByExecutionTimeBefore(commandCutoffCaptor.capture());
        assertEquals(LocalDateTime.of(2025, 12, 10, 0, 0), commandCutoffCaptor.getValue());

        ArgumentCaptor<LocalDateTime> chatCutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(chatMessageRepository).deleteByCreatedAtBefore(chatCutoffCaptor.capture());
        assertEquals(LocalDateTime.of(2025, 12, 10, 0, 0), chatCutoffCaptor.getValue());
    }

    @Test
    void cleanupExpiredLogs_shouldUseCurrentClockValueOnEachRun() {
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));
        CommandLogRepository commandLogRepository = mock(CommandLogRepository.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        when(commandLogRepository.deleteByExecutionTimeBefore(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(0);
        when(chatMessageRepository.deleteByCreatedAtBefore(org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(0);

        CommandLogCleanupService service = new CommandLogCleanupService(commandLogRepository, chatMessageRepository, clock, 90);
        service.cleanupExpiredLogs();
        clock.advance(Duration.ofDays(2));
        service.cleanupExpiredLogs();

        ArgumentCaptor<LocalDateTime> commandCutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(commandLogRepository, org.mockito.Mockito.times(2)).deleteByExecutionTimeBefore(commandCutoffCaptor.capture());
        assertEquals(LocalDateTime.of(2025, 12, 10, 0, 0), commandCutoffCaptor.getAllValues().get(0));
        assertEquals(LocalDateTime.of(2025, 12, 12, 0, 0), commandCutoffCaptor.getAllValues().get(1));

        ArgumentCaptor<LocalDateTime> chatCutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(chatMessageRepository, org.mockito.Mockito.times(2)).deleteByCreatedAtBefore(chatCutoffCaptor.capture());
        assertEquals(LocalDateTime.of(2025, 12, 10, 0, 0), chatCutoffCaptor.getAllValues().get(0));
        assertEquals(LocalDateTime.of(2025, 12, 12, 0, 0), chatCutoffCaptor.getAllValues().get(1));
    }

    @Test
    void constructor_invalidRetention_shouldThrow() {
        CommandLogRepository commandLogRepository = mock(CommandLogRepository.class);
        ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
        MutableClock clock = new MutableClock(Instant.parse("2026-03-10T00:00:00Z"));

        assertThrows(IllegalArgumentException.class,
                () -> new CommandLogCleanupService(commandLogRepository, chatMessageRepository, clock, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CommandLogCleanupService(commandLogRepository, chatMessageRepository, clock, -1));
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant initial) {
            this(initial, ZoneId.of("UTC"));
        }

        private MutableClock(Instant initial, ZoneId zone) {
            this.instant = initial;
            this.zone = zone;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
