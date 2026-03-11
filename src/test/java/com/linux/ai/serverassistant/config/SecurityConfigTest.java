package com.linux.ai.serverassistant.config;

import org.junit.jupiter.api.Test;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.security.SessionAuthenticationSignatureService;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.io.IOException;
import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SecurityConfigTest {

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void blankCorsAllowedOriginsShouldFallbackToLocalhostDevOrigin() {
        SecurityConfig securityConfig = new SecurityConfig("");

        CorsConfiguration corsConfiguration = loadCorsConfiguration(securityConfig);

        assertEquals(List.of("http://localhost:5173"), corsConfiguration.getAllowedOrigins());
    }

    @Test
    void nullCorsAllowedOriginsShouldFallbackToLocalhostDevOrigin() {
        SecurityConfig securityConfig = new SecurityConfig(null);

        CorsConfiguration corsConfiguration = loadCorsConfiguration(securityConfig);

        assertEquals(List.of("http://localhost:5173"), corsConfiguration.getAllowedOrigins());
    }

    @Test
    void commaOnlyCorsAllowedOriginsShouldFailFast() {
        assertThrows(IllegalStateException.class, () -> new SecurityConfig(" , , "));
    }

    @Test
    void configuredCorsAllowedOriginsShouldBeTrimmed() {
        SecurityConfig securityConfig = new SecurityConfig("https://admin.example.com, http://localhost:3000");

        CorsConfiguration corsConfiguration = loadCorsConfiguration(securityConfig);

        assertEquals(
                List.of("https://admin.example.com", "http://localhost:3000"),
                corsConfiguration.getAllowedOrigins()
        );
    }

    @Test
    void sessionUserAuthenticationFilterShouldRebuildAuthenticationWhenSignatureIsValid() throws Exception {
        AdminAuthorizationService adminAuthorizationService = mock(AdminAuthorizationService.class);
        when(adminAuthorizationService.isAdmin("alice")).thenReturn(true);
        SessionAuthenticationSignatureService signatureService =
                SessionAuthenticationSignatureService.forTesting("test-session-signing-secret");
        SecurityConfig.SessionUserAuthenticationFilter filter =
                new SecurityConfig.SessionUserAuthenticationFilter(adminAuthorizationService, signatureService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/status");
        var session = request.getSession(true);
        session.setAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE, "alice");
        session.setAttribute(
                SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE,
                signatureService.sign("alice", session.getId())
        );

        runFilter(filter, request);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        assertEquals("alice", auth.getName());
        assertEquals(List.of("ROLE_ADMIN", "ROLE_USER"), auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
                .sorted()
                .toList());
        verify(adminAuthorizationService).isAdmin("alice");
    }

    @Test
    void sessionUserAuthenticationFilterShouldRejectTamperedSessionMarker() throws Exception {
        AdminAuthorizationService adminAuthorizationService = mock(AdminAuthorizationService.class);
        SessionAuthenticationSignatureService signatureService =
                SessionAuthenticationSignatureService.forTesting("test-session-signing-secret");
        SecurityConfig.SessionUserAuthenticationFilter filter =
                new SecurityConfig.SessionUserAuthenticationFilter(adminAuthorizationService, signatureService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/status");
        var session = request.getSession(true);
        session.setAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE, "alice");
        session.setAttribute(
                SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE,
                "forged-signature"
        );

        runFilter(filter, request);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
        assertNull(session.getAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE));
        assertNull(session.getAttribute(SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE));
        verify(adminAuthorizationService, never()).isAdmin("alice");
    }

    @Test
    void sessionUserAuthenticationFilterShouldRejectNonStringSessionMarker() throws Exception {
        AdminAuthorizationService adminAuthorizationService = mock(AdminAuthorizationService.class);
        SessionAuthenticationSignatureService signatureService =
                SessionAuthenticationSignatureService.forTesting("test-session-signing-secret");
        SecurityConfig.SessionUserAuthenticationFilter filter =
                new SecurityConfig.SessionUserAuthenticationFilter(adminAuthorizationService, signatureService);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/status");
        var session = request.getSession(true);
        session.setAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE, Map.of("user", "alice"));
        session.setAttribute(
                SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE,
                signatureService.sign("alice", session.getId())
        );

        runFilter(filter, request);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNull(auth);
        assertNull(session.getAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE));
        assertNull(session.getAttribute(SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE));
        verify(adminAuthorizationService, never()).isAdmin("alice");
    }

    private CorsConfiguration loadCorsConfiguration(SecurityConfig securityConfig) {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        return source.getCorsConfiguration(new MockHttpServletRequest("GET", "/api/status"));
    }

    private void runFilter(SecurityConfig.SessionUserAuthenticationFilter filter, MockHttpServletRequest request)
            throws jakarta.servlet.ServletException, IOException {
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
