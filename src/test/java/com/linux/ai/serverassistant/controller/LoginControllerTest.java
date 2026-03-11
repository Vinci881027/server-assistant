package com.linux.ai.serverassistant.controller;

import com.linux.ai.serverassistant.dto.ApiResponse;
import com.linux.ai.serverassistant.dto.LoginRequest;
import com.linux.ai.serverassistant.security.SecureCredentialStore;
import com.linux.ai.serverassistant.service.LinuxAuthService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.security.LoginAttemptService;
import com.linux.ai.serverassistant.service.security.SessionAuthenticationSignatureService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LoginControllerTest {

    private LinuxAuthService authService;
    private SecureCredentialStore credentialStore;
    private AdminAuthorizationService adminAuthorizationService;
    private LoginAttemptService loginAttemptService;
    private SessionAuthenticationSignatureService sessionAuthenticationSignatureService;
    private LoginController controller;

    @BeforeEach
    void setUp() {
        authService = mock(LinuxAuthService.class);
        credentialStore = mock(SecureCredentialStore.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);
        loginAttemptService = mock(LoginAttemptService.class);
        sessionAuthenticationSignatureService = mock(SessionAuthenticationSignatureService.class);
        controller = new LoginController(
                authService,
                credentialStore,
                adminAuthorizationService,
                loginAttemptService,
                sessionAuthenticationSignatureService
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void login_whenPreCheckBlocked_shouldReturn429WithoutAuthenticating() {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(loginAttemptService.extractClientIp(httpRequest)).thenReturn("203.0.113.10");
        when(loginAttemptService.checkBlocked("alice", "203.0.113.10"))
                .thenReturn(LoginAttemptService.LoginThrottleStatus.blocked(30));

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.login(loginRequest("alice", "bad-pass"), httpRequest, session);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("30", response.getHeaders().getFirst("Retry-After"));
        assertNotNull(response.getBody());
        assertEquals("LOGIN_RATE_LIMITED", response.getBody().getError().getCode());
        assertNotNull(response.getBody().getData());
        assertEquals(30L, ((Number) response.getBody().getData().get("retryAfterSeconds")).longValue());
        verifyNoInteractions(authService);
    }

    @Test
    void login_whenAuthFailsAndNowBlocked_shouldReturn429() {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(loginAttemptService.extractClientIp(httpRequest)).thenReturn("198.51.100.7");
        when(loginAttemptService.checkBlocked("alice", "198.51.100.7"))
                .thenReturn(LoginAttemptService.LoginThrottleStatus.allowed());
        when(authService.authenticate(eq("alice"), any(char[].class)))
                .thenReturn(false);
        when(loginAttemptService.recordFailure("alice", "198.51.100.7"))
                .thenReturn(LoginAttemptService.LoginThrottleStatus.blocked(120));

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.login(loginRequest("alice", "wrong"), httpRequest, session);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("120", response.getHeaders().getFirst("Retry-After"));
        assertNotNull(response.getBody());
        assertEquals("LOGIN_RATE_LIMITED", response.getBody().getError().getCode());
        assertNotNull(response.getBody().getData());
        assertEquals(120L, ((Number) response.getBody().getData().get("retryAfterSeconds")).longValue());
        verify(authService).authenticate(eq("alice"), any(char[].class));
    }

    @Test
    void login_whenAuthFailsButNotBlocked_shouldReturn401() {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        HttpSession session = mock(HttpSession.class);
        when(loginAttemptService.extractClientIp(httpRequest)).thenReturn("198.51.100.8");
        when(loginAttemptService.checkBlocked("alice", "198.51.100.8"))
                .thenReturn(LoginAttemptService.LoginThrottleStatus.allowed());
        when(authService.authenticate(eq("alice"), any(char[].class)))
                .thenReturn(false);
        when(loginAttemptService.recordFailure("alice", "198.51.100.8"))
                .thenReturn(LoginAttemptService.LoginThrottleStatus.allowed());

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.login(loginRequest("alice", "wrong"), httpRequest, session);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("AUTH_FAILED", response.getBody().getError().getCode());
    }

    @Test
    void login_whenAuthSucceeds_shouldResetAttemptsAndReturn200() {
        HttpServletRequest httpRequest = mock(HttpServletRequest.class);
        HttpSession oldSession = mock(HttpSession.class);
        HttpSession newSession = mock(HttpSession.class);

        when(loginAttemptService.extractClientIp(httpRequest)).thenReturn("203.0.113.50");
        when(loginAttemptService.checkBlocked("alice", "203.0.113.50"))
                .thenReturn(LoginAttemptService.LoginThrottleStatus.allowed());
        when(authService.authenticate(eq("alice"), any(char[].class)))
                .thenReturn(true);
        when(oldSession.getId()).thenReturn("old-session-id");
        when(httpRequest.getSession(true)).thenReturn(newSession);
        when(newSession.getId()).thenReturn("new-session-id");
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(false);
        when(sessionAuthenticationSignatureService.sign("alice", "new-session-id")).thenReturn("signed-marker");

        ResponseEntity<ApiResponse<Map<String, Object>>> response =
                controller.login(loginRequest("alice", "correct"), httpRequest, oldSession);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("登入成功", response.getBody().getMessage());

        verify(loginAttemptService).recordSuccess("alice");
        verify(oldSession).invalidate();
        verify(httpRequest).getSession(true);
        verify(credentialStore).removeCredential("old-session-id");
        verify(credentialStore).storeCredential(eq("new-session-id"), eq("alice"), any(char[].class));
        verify(newSession).setAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE, "alice");
        verify(newSession).setAttribute(
                SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE,
                "signed-marker"
        );
        verify(loginAttemptService, never()).recordFailure(anyString(), anyString());
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password.toCharArray());
        return request;
    }
}
