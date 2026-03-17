package com.linux.ai.serverassistant.controller;

import com.linux.ai.serverassistant.service.LinuxAuthService;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.security.LoginAttemptService;
import com.linux.ai.serverassistant.service.security.SessionAuthenticationSignatureService;
import com.linux.ai.serverassistant.security.SecureCredentialStore;
import com.linux.ai.serverassistant.dto.ApiResponse;
import com.linux.ai.serverassistant.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class LoginController {

    private final LinuxAuthService authService;
    private final SecureCredentialStore credentialStore;
    private final AdminAuthorizationService adminAuthorizationService;
    private final LoginAttemptService loginAttemptService;
    private final SessionAuthenticationSignatureService sessionAuthenticationSignatureService;

    public LoginController(LinuxAuthService authService,
                           SecureCredentialStore credentialStore,
                           AdminAuthorizationService adminAuthorizationService,
                           LoginAttemptService loginAttemptService,
                           SessionAuthenticationSignatureService sessionAuthenticationSignatureService) {
        this.authService = authService;
        this.credentialStore = credentialStore;
        this.adminAuthorizationService = adminAuthorizationService;
        this.loginAttemptService = loginAttemptService;
        this.sessionAuthenticationSignatureService = sessionAuthenticationSignatureService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpSession session) {
        char[] requestPassword = (request == null) ? null : request.getPassword();
        if (request == null || request.getUsername() == null || requestPassword == null) {
            if (requestPassword != null) {
                Arrays.fill(requestPassword, '\0');
            }
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("請提供使用者名稱與密碼", "INVALID_LOGIN_REQUEST"));
        }

        char[] authPassword = null;
        char[] storedPassword = null;

        try {
            String username = request.getUsername().trim();
            String clientIp = loginAttemptService.extractClientIp(httpRequest);

            if (username.isBlank() || isBlank(requestPassword)) {
                return ResponseEntity.badRequest()
                        .body(ApiResponse.error("使用者名稱與密碼不可為空白", "INVALID_LOGIN_REQUEST"));
            }

            LoginAttemptService.LoginThrottleStatus preCheck = loginAttemptService.checkBlocked(username, clientIp);
            if (preCheck.blocked()) {
                return tooManyLoginAttempts(preCheck.retryAfterSeconds());
            }

            authPassword = Arrays.copyOf(requestPassword, requestPassword.length);
            if (authService.authenticate(username, authPassword)) {
                loginAttemptService.recordSuccess(username);
                // Invalidate old session and create a fresh one to prevent session fixation race windows
                String oldSessionId = session.getId();
                session.invalidate();
                credentialStore.removeCredential(oldSessionId);
                HttpSession currentSession = httpRequest.getSession(true);
                if (currentSession == null) {
                    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .body(ApiResponse.error("登入成功但新會話建立失敗，請重試", "SESSION_ROTATION_FAILED"));
                }

                // 1. Existing Session logic (for SystemService sudo usage)
                currentSession.setAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE, username);
                currentSession.setAttribute(
                        SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE,
                        sessionAuthenticationSignatureService.sign(username, currentSession.getId())
                );

                storedPassword = Arrays.copyOf(requestPassword, requestPassword.length);
                try {
                    credentialStore.storeCredential(currentSession.getId(), username, storedPassword);
                } finally {
                    Arrays.fill(storedPassword, '\0');
                    storedPassword = null;
                }

                // 2. Spring Security integration: Manually register authentication information
                // Determine if the user is an admin and assign corresponding roles
                boolean isAdmin = adminAuthorizationService.isAdmin(username);
                String[] roles = isAdmin ? new String[]{"USER", "ADMIN"} : new String[]{"USER"};

                // Create a standard UserDetails (no DB lookup needed here as PAM has already verified)
                UserDetails userDetails = User.withUsername(username)
                        .password("") // Password is not stored in SecurityContext to prevent accidental leakage
                        .roles(roles)
                        .build();

                Authentication auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(auth);
                SecurityContextHolder.setContext(context);

                // Important: Spring Security 6 requires manually saving the Context back to the Session, otherwise it will be lost after refresh
                currentSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

                Map<String, Object> data = new HashMap<>();
                data.put("user", username);
                data.put("isAdmin", isAdmin);

                return ResponseEntity.ok(ApiResponse.success("登入成功", data));
            }

            LoginAttemptService.LoginThrottleStatus failureStatus = loginAttemptService.recordFailure(username, clientIp);
            if (failureStatus.blocked()) {
                return tooManyLoginAttempts(failureStatus.retryAfterSeconds());
            }
            Map<String, Object> failData = new HashMap<>();
            failData.put("remainingAttempts", failureStatus.remainingAttempts());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error("登入失敗：使用者名稱或密碼錯誤", "AUTH_FAILED", failData));
        } finally {
            if (authPassword != null) {
                Arrays.fill(authPassword, '\0');
            }
            if (storedPassword != null) {
                Arrays.fill(storedPassword, '\0');
            }
            if (requestPassword != null) {
                Arrays.fill(requestPassword, '\0');
            }
        }
    }

    @GetMapping("/status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStatus(HttpSession session) {
        String user = (String) session.getAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE);
        SecurityContext context = SecurityContextHolder.getContext();
        Authentication authentication = context != null ? context.getAuthentication() : null;
        String principalName = authentication != null ? authentication.getName() : null;
        boolean authenticated = authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
        boolean principalMatches = authenticated
                && principalName != null
                && principalName.equals(user);

        if (user != null && principalMatches && authentication != null) {
            boolean isAdmin = authentication.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

            Map<String, Object> data = new HashMap<>();
            data.put("user", user);
            data.put("isAdmin", isAdmin);

            return ResponseEntity.ok(ApiResponse.success(data));
        }

        if (user != null) {
            // Clean up stale session marker to avoid false "logged in" UI state.
            session.removeAttribute(SessionAuthenticationSignatureService.SESSION_USER_ATTRIBUTE);
            session.removeAttribute(SessionAuthenticationSignatureService.SESSION_USER_SIGNATURE_ATTRIBUTE);
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("未登入", "NOT_AUTHENTICATED"));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpSession session) {
        // Clear the encrypted stored password
        credentialStore.removeCredential(session.getId());

        session.invalidate();
        SecurityContextHolder.clearContext();
        return ResponseEntity.ok(ApiResponse.success("登出成功"));
    }

    private ResponseEntity<ApiResponse<Map<String, Object>>> tooManyLoginAttempts(long retryAfterSeconds) {
        long normalizedRetryAfterSeconds = Math.max(1, retryAfterSeconds);
        Map<String, Object> data = new HashMap<>();
        data.put("retryAfterSeconds", normalizedRetryAfterSeconds);

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(normalizedRetryAfterSeconds))
                .body(ApiResponse.error("登入嘗試次數過多，請稍後再試", "LOGIN_RATE_LIMITED", data));
    }

    private boolean isBlank(char[] value) {
        if (value.length == 0) {
            return true;
        }
        for (char c : value) {
            if (!Character.isWhitespace(c)) {
                return false;
            }
        }
        return true;
    }
}
