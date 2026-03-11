package com.linux.ai.serverassistant.service.command;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExclamationCommandRouterTest {

    private final ExclamationCommandRouter router = new ExclamationCommandRouter();

    @Test
    void nonExclamationMessage_shouldNotMatch() {
        Optional<DeterministicRouter.Route> r = router.route(
                new DeterministicRouter.Context("docker ps", "c1", "alice"));

        assertTrue(r.isEmpty());
    }

    @Test
    void emptyExclamationCommand_shouldReturnUsageText() {
        Optional<DeterministicRouter.Route> r = router.route(
                new DeterministicRouter.Context("!   ", "c1", "alice"));

        assertTrue(r.isPresent());
        assertInstanceOf(DeterministicRouter.AssistantText.class, r.get());
        String text = ((DeterministicRouter.AssistantText) r.get()).text();
        assertTrue(text.contains("!docker ps"));
    }

    @Test
    void exclamationCommand_shouldReturnLinuxCommandRoute() {
        Optional<DeterministicRouter.Route> r = router.route(
                new DeterministicRouter.Context("!docker ps", "c1", "alice"));

        assertTrue(r.isPresent());
        assertInstanceOf(DeterministicRouter.LinuxCommand.class, r.get());
        DeterministicRouter.LinuxCommand cmd = (DeterministicRouter.LinuxCommand) r.get();
        assertEquals("docker ps", cmd.command());
        assertFalse(cmd.confirm());
    }

    @Test
    void exclamationCommand_shouldTrimWhitespaceAfterPrefix() {
        Optional<DeterministicRouter.Route> r = router.route(
                new DeterministicRouter.Context("!   ls -la /tmp", "c1", "alice"));

        assertTrue(r.isPresent());
        assertInstanceOf(DeterministicRouter.LinuxCommand.class, r.get());
        DeterministicRouter.LinuxCommand cmd = (DeterministicRouter.LinuxCommand) r.get();
        assertEquals("ls -la /tmp", cmd.command());
    }
}
