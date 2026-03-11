package com.linux.ai.serverassistant.service.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class SessionAuthenticationSignatureService {

    public static final String SESSION_USER_ATTRIBUTE = "user";
    public static final String SESSION_USER_SIGNATURE_ATTRIBUTE = "user_signature";

    private static final Logger log = LoggerFactory.getLogger(SessionAuthenticationSignatureService.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String TOKEN_PREFIX = "v1.";
    private static final int RANDOM_SECRET_BYTES = 32;
    private static final SecureRandom SHARED_SECURE_RANDOM = new SecureRandom();

    private final byte[] signingKey;

    @Autowired
    public SessionAuthenticationSignatureService(
            @Value("${app.security.session.signature-secret:}") String configuredSecret) {
        this.signingKey = resolveSigningKey(configuredSecret);
    }

    public static SessionAuthenticationSignatureService forTesting(String configuredSecret) {
        return new SessionAuthenticationSignatureService(configuredSecret, true);
    }

    private SessionAuthenticationSignatureService(String configuredSecret, boolean ignored) {
        this.signingKey = resolveSigningKey(configuredSecret);
    }

    public String sign(String username, String sessionId) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("username and sessionId must be non-empty");
        }

        byte[] digest = computeHmac(payload(username, sessionId));
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }

    public boolean isValid(String username, String sessionId, String signature) {
        if (!StringUtils.hasText(username)
                || !StringUtils.hasText(sessionId)
                || !StringUtils.hasText(signature)) {
            return false;
        }

        String expected = sign(username, sessionId);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] providedBytes = signature.trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedBytes, providedBytes);
    }

    private byte[] computeHmac(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute session signature", ex);
        }
    }

    private String payload(String username, String sessionId) {
        return username + ":" + sessionId;
    }

    private byte[] resolveSigningKey(String configuredSecret) {
        if (StringUtils.hasText(configuredSecret)) {
            return configuredSecret.trim().getBytes(StandardCharsets.UTF_8);
        }

        byte[] randomKey = new byte[RANDOM_SECRET_BYTES];
        SHARED_SECURE_RANDOM.nextBytes(randomKey);
        log.warn("app.security.session.signature-secret is empty. Using random in-memory key; login sessions become invalid after restart.");
        return randomKey;
    }
}
