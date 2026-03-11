package com.linux.ai.serverassistant.service.command;

import com.linux.ai.serverassistant.security.SecureCredentialStore;
import com.linux.ai.serverassistant.util.CommandMarkers;
import com.linux.ai.serverassistant.util.UserContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Resolves user session credentials and injects sudo password into process stdin.
 *
 * Encapsulates security-sensitive credential handling in one auditable place:
 * identity resolution, password retrieval from encrypted store, and
 * password injection via process stdin (no shell escaping).
 */
@Service
public class SudoCredentialInjector {

    private final UserContext userContext;
    private final SecureCredentialStore credentialStore;

    /**
     * Outcome of sudo password injection.
     *
     * @param skipped     true when injection is intentionally skipped (trusted root execution)
     * @param bytesWritten number of bytes written to stdin (includes trailing newline)
     */
    public record InjectionResult(boolean skipped, int bytesWritten) {
        static InjectionResult skippedResult() {
            return new InjectionResult(true, 0);
        }

        static InjectionResult injectedResult(int bytesWritten) {
            return new InjectionResult(false, Math.max(0, bytesWritten));
        }

        public boolean wasInjected() {
            return !skipped && bytesWritten > 0;
        }
    }

    /**
     * Result of session credential resolution.
     *
     * @param sessionUser   the Linux username to run the command as (null for root execution)
     * @param passChars     the sudo password chars (null for root execution or on failure)
     * @param auditUsername username used for audit log attribution
     * @param failureReason null on success; non-null error message on credential failure
     */
    public record ResolvedSession(
            String sessionUser,
            char[] passChars,
            String auditUsername,
            String failureReason) {

        public boolean failed() {
            return failureReason != null;
        }

        static ResolvedSession forRoot(String actorUsername) {
            return new ResolvedSession(null, null, actorUsername, null);
        }

        static ResolvedSession failure(String auditUsername, String reason) {
            return new ResolvedSession(null, null, auditUsername, reason);
        }
    }

    @Autowired
    public SudoCredentialInjector(UserContext userContext, SecureCredentialStore credentialStore) {
        this.userContext = userContext;
        this.credentialStore = credentialStore;
    }

    /**
     * Resolves the session identity and retrieves sudo credentials for a user command.
     *
     * @param forceRootExecution true to skip identity resolution and run as root
     * @param actorUsername      optional explicit username; falls back to session context
     * @return resolved session, or a failed session with error message
     */
    public ResolvedSession resolveSession(boolean forceRootExecution, String actorUsername) {
        if (forceRootExecution) {
            return ResolvedSession.forRoot(actorUsername);
        }

        UserContext.ResolvedIdentity identity = userContext.resolveIdentity(actorUsername);
        String sessionUser = identity.username();
        String sessionId = identity.sessionId();
        String auditUsername = sessionUser;
        char[] passChars = null;

        if (sessionUser != null && !sessionUser.isBlank() && sessionId != null && !sessionId.isBlank()) {
            Optional<char[]> pass = credentialStore.retrievePassword(sessionId, sessionUser);
            passChars = (pass != null) ? pass.orElse(null) : null;
        }

        if (sessionUser == null || sessionUser.isBlank() || passChars == null) {
            String reason = (sessionUser == null || sessionUser.isBlank())
                    ? CommandMarkers.SECURITY_VIOLATION + " 無法取得登入使用者資訊，請重新登入後再試。"
                    : CommandMarkers.SECURITY_VIOLATION + " 使用者憑證已失效，請重新登入後再試。";
            return ResolvedSession.failure(auditUsername, reason);
        }

        return new ResolvedSession(sessionUser, passChars, auditUsername, null);
    }

    /**
     * Writes the sudo password to the process stdin and zeroes all sensitive buffers.
     *
     * @param forceRootExecution skip injection when running as root (no sudo needed)
     * @param passChars          password chars; cleared after injection regardless of outcome
     * @param process            the process whose stdin receives the password
     */
    public InjectionResult injectPassword(boolean forceRootExecution, char[] passChars, Process process)
            throws IOException {
        if (forceRootExecution || passChars == null) {
            clearSensitiveChars(passChars);
            return InjectionResult.skippedResult();
        }
        if (passChars.length == 0) {
            clearSensitiveChars(passChars);
            throw new IllegalStateException("Sudo 驗證失敗：密碼為空，已取消執行。");
        }
        if (process == null) {
            clearSensitiveChars(passChars);
            throw new IllegalArgumentException("Sudo 驗證失敗：找不到可注入密碼的程序。");
        }
        try {
            try (OutputStream os = process.getOutputStream()) {
                ByteBuffer passwordBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passChars));
                byte[] passBytes = new byte[passwordBuffer.remaining() + 1];
                try {
                    int passLen = passwordBuffer.remaining();
                    passwordBuffer.get(passBytes, 0, passLen);
                    passBytes[passLen] = (byte) '\n';
                    os.write(passBytes);
                    os.flush();
                    return InjectionResult.injectedResult(passLen + 1);
                } finally {
                    Arrays.fill(passBytes, (byte) 0);
                    zeroOutByteBuffer(passwordBuffer);
                }
            }
        } finally {
            clearSensitiveChars(passChars);
        }
    }

    public static void clearSensitiveChars(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }

    private static void zeroOutByteBuffer(ByteBuffer buffer) {
        if (buffer == null) {
            return;
        }
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
            return;
        }
        if (buffer.isReadOnly()) {
            return;
        }
        int limit = buffer.limit();
        for (int i = 0; i < limit; i++) {
            buffer.put(i, (byte) 0);
        }
    }
}
