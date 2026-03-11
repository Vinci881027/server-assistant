package com.linux.ai.serverassistant.service.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandRiskyOperationServiceTest {

    private SlashCommandRiskyOperationService service;

    @BeforeEach
    void setUp() {
        service = new SlashCommandRiskyOperationService(new PendingConfirmationManager());
    }

    @Test
    void offloadConfirmCommand_roundTrip_shouldParse() {
        String command = SlashCommandRiskyOperationService.buildOffloadConfirmCommand(
                Path.of("/home/alice/work"),
                Path.of("/mnt/archive")
        );

        OffloadConfirmPayload payload = SlashCommandRiskyOperationService.parseOffloadConfirmCommand(command).orElseThrow();
        assertEquals(Path.of("/home/alice/work"), payload.source());
        assertEquals(Path.of("/mnt/archive"), payload.targetRoot());
    }

    @Test
    void mountConfirmCommand_invalidDevice_shouldNotParse() {
        String invalid = "mount-confirm --device /tmp/a --target /mnt/data --fstype ext4 --options defaults,nofail";
        assertTrue(SlashCommandRiskyOperationService.parseMountConfirmCommand(invalid).isEmpty());
    }

    @Test
    void storeAndConsumeMountPendingConfirmation_shouldWork() {
        String command = "mount-confirm --device /dev/sdc --target /mnt/data --fstype ext4 --options defaults,nofail";

        service.storeMountPendingConfirmation("alice", command);

        assertTrue(service.consumeMountPendingConfirmation("alice", command));
        assertFalse(service.consumeMountPendingConfirmation("alice", command));
    }
}
