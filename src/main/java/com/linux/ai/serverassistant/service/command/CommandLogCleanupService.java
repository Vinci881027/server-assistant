package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.repository.ChatMessageRepository;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
public class CommandLogCleanupService {

    private static final Logger log = LoggerFactory.getLogger(CommandLogCleanupService.class);

    private final CommandLogRepository commandLogRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final Clock clock;
    private final int retentionDays;

    @Autowired
    public CommandLogCleanupService(
            CommandLogRepository commandLogRepository,
            ChatMessageRepository chatMessageRepository,
            @Value("${app.command.audit-log.retention-days:90}") int retentionDays) {
        this(commandLogRepository, chatMessageRepository, Clock.systemDefaultZone(), retentionDays);
    }

    CommandLogCleanupService(
            CommandLogRepository commandLogRepository,
            ChatMessageRepository chatMessageRepository,
            Clock clock,
            int retentionDays) {
        this.commandLogRepository = Objects.requireNonNull(commandLogRepository, "commandLogRepository");
        this.chatMessageRepository = Objects.requireNonNull(chatMessageRepository, "chatMessageRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retentionDays <= 0) {
            throw new IllegalArgumentException("retentionDays must be > 0");
        }
        this.retentionDays = retentionDays;
    }

    @Scheduled(fixedRateString = "${app.command.audit-log.cleanup-interval-ms:86400000}")
    public void cleanupExpiredLogs() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(retentionDays);
        int deletedCommandLogs = commandLogRepository.deleteByExecutionTimeBefore(cutoff);
        int deletedChatMessages = chatMessageRepository.deleteByCreatedAtBefore(cutoff);

        if (deletedCommandLogs > 0 || deletedChatMessages > 0) {
            log.info(
                    "Deleted expired records older than {} days (cutoff={}): command_logs={}, chat_messages={}",
                    retentionDays,
                    cutoff,
                    deletedCommandLogs,
                    deletedChatMessages
            );
        }
    }
}
