package com.linux.ai.serverassistant.integration;

import com.linux.ai.serverassistant.service.command.CommandConfirmationService;
import com.linux.ai.serverassistant.service.command.CommandExecutionService;
import com.linux.ai.serverassistant.service.security.SessionAuthenticationSignatureService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ConfirmCommandFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CommandExecutionService commandExecutionService;

    @Autowired
    private CommandConfirmationService commandConfirmationService;

    @Autowired
    private SessionAuthenticationSignatureService sessionAuthenticationSignatureService;

    @BeforeEach
    void setUp() {
        commandConfirmationService.clearAllPendingConfirmations();
    }

    @Test
    void confirmCommand_shouldConsumePendingRecord() throws Exception {
        String username = "alice";
        String command = "rm -rf /srv/confirm-flow-e2e-consume";
        MockHttpSession session = authenticatedSession(username);
        CsrfTokenContext csrf = fetchCsrfToken(session);

        CommandExecutionService.ExecutionResult initialResult = commandExecutionService.executeWithResult(
                command,
                CommandExecutionService.ExecutionOptions.builder()
                        .user(username)
                        .build());

        assertTrue(initialResult.isPendingConfirmation());
        assertTrue(commandConfirmationService.hasPendingConfirmation(command, username));

        mockMvc.perform(post("/api/ai/confirm-command")
                        .session(session)
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmPayload(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isString());

        assertFalse(commandConfirmationService.hasPendingConfirmation(command, username));
    }

    @Test
    void confirmCommand_shouldRejectReplayAfterPendingConsumed() throws Exception {
        String username = "alice";
        String command = "rm -rf /srv/confirm-flow-e2e-replay";
        MockHttpSession session = authenticatedSession(username);
        CsrfTokenContext csrf = fetchCsrfToken(session);

        CommandExecutionService.ExecutionResult initialResult = commandExecutionService.executeWithResult(
                command,
                CommandExecutionService.ExecutionOptions.builder()
                        .user(username)
                        .build());

        assertTrue(initialResult.isPendingConfirmation());

        mockMvc.perform(post("/api/ai/confirm-command")
                        .session(session)
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmPayload(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(post("/api/ai/confirm-command")
                        .session(session)
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmPayload(command)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value(containsString("找不到此指令的待確認紀錄")));

        assertFalse(commandConfirmationService.hasPendingConfirmation(command, username));
    }

    @Test
    void confirmCommand_concurrentRequests_shouldConsumePendingOnlyOnce() throws Exception {
        String username = "alice";
        String command = "rm -rf /srv/confirm-flow-e2e-race";
        MockHttpSession sessionA = authenticatedSession(username);
        MockHttpSession sessionB = authenticatedSession(username);
        CsrfTokenContext csrfA = fetchCsrfToken(sessionA);
        CsrfTokenContext csrfB = fetchCsrfToken(sessionB);

        CommandExecutionService.ExecutionResult initialResult = commandExecutionService.executeWithResult(
                command,
                CommandExecutionService.ExecutionOptions.builder()
                        .user(username)
                        .build());

        assertTrue(initialResult.isPendingConfirmation());
        assertTrue(commandConfirmationService.hasPendingConfirmation(command, username));

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ConcurrentResponse> first = executor.submit(() ->
                    submitConfirmRequestConcurrently(startLatch, sessionA, csrfA, command));
            Future<ConcurrentResponse> second = executor.submit(() ->
                    submitConfirmRequestConcurrently(startLatch, sessionB, csrfB, command));

            startLatch.countDown();
            ConcurrentResponse responseA = first.get(5, TimeUnit.SECONDS);
            ConcurrentResponse responseB = second.get(5, TimeUnit.SECONDS);

            assertTrue(responseA.status() == 200 || responseA.status() == 400);
            assertTrue(responseB.status() == 200 || responseB.status() == 400);

            boolean hasSuccessfulExecutionResponse = Stream.of(responseA, responseB)
                    .anyMatch(r -> r.body().contains("\"success\":true"));
            assertTrue(hasSuccessfulExecutionResponse);

            boolean hasReplayOrDuplicateProtectionResponse = Stream.of(responseA, responseB).anyMatch(r ->
                    (r.status() == 400 && r.body().contains("此指令正在處理中"))
                            || (r.status() == 200 && r.body().contains("找不到此指令的待確認紀錄")));
            assertTrue(hasReplayOrDuplicateProtectionResponse);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertFalse(commandConfirmationService.hasPendingConfirmation(command, username));
    }

    private static String confirmPayload(String command) {
        return """
                {
                  "command": "%s"
                }
                """.formatted(command);
    }

    private MockHttpSession authenticatedSession(String username) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(
                SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE,
                username);
        session.setAttribute(
                SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE,
                sessionAuthenticationSignatureService.sign(username, session.getId()));
        return session;
    }

    private CsrfTokenContext fetchCsrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/ping").session(session))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(csrfCookie, "XSRF-TOKEN cookie should be present");
        return new CsrfTokenContext(csrfCookie, csrfCookie.getValue());
    }

    private ConcurrentResponse submitConfirmRequestConcurrently(
            CountDownLatch startLatch,
            MockHttpSession session,
            CsrfTokenContext csrf,
            String command) throws Exception {
        assertTrue(startLatch.await(3, TimeUnit.SECONDS));
        MvcResult result = mockMvc.perform(post("/api/ai/confirm-command")
                        .session(session)
                        .cookie(csrf.cookie())
                        .header("X-XSRF-TOKEN", csrf.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmPayload(command)))
                .andReturn();
        return new ConcurrentResponse(
                result.getResponse().getStatus(),
                result.getResponse().getContentAsString());
    }

    private record CsrfTokenContext(Cookie cookie, String token) {
    }

    private record ConcurrentResponse(int status, String body) {
    }
}
