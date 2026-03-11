package com.linux.ai.serverassistant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Secure credential storage service.
 * Stores user passwords with AES-256-GCM and a 1-day auto-expiry policy.
 *
 * Security features:
 * - AES-256-GCM authenticated encryption
 * - Unique random IV per password
 * - Encrypted storage in memory
 * - Automatic expiry cleanup (1 day)
 * - Zero out char arrays after use
 */
@Service
public class SecureCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(SecureCredentialStore.class);

    private static final int EXPIRY_MINUTES = 24 * 60;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int GCM_IV_LENGTH = 12;
    private static final SecureRandom SHARED_SECURE_RANDOM = new SecureRandom();

    // Store encrypted credentials
    private final Map<String, EncryptedCredential> credentialStore = new ConcurrentHashMap<>();

    // Application-level master key (should be loaded from secure config)
    private final SecretKey masterKey;
    private final SecureRandom secureRandom = SHARED_SECURE_RANDOM;

    public SecureCredentialStore() {
        try {
            String envKey = System.getenv("CREDENTIAL_STORE_KEY");
            if (envKey != null && !envKey.isBlank()) {
                this.masterKey = parseMasterKey(envKey);
                log.info("SecureCredentialStore initialized with persistent master key from environment");
            } else {
                // Auto-generate a random key at startup. This is safe because the credential store
                // is purely in-memory — all encrypted data is lost on restart anyway (along with sessions).
                byte[] randomKey = new byte[32];
                this.secureRandom.nextBytes(randomKey);
                this.masterKey = new SecretKeySpec(randomKey, "AES");
                Arrays.fill(randomKey, (byte) 0);
                log.info("SecureCredentialStore initialized with auto-generated ephemeral AES-256 key");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SecureCredentialStore", e);
        }
    }

    SecureCredentialStore(String base64MasterKey) {
        try {
            this.masterKey = parseMasterKey(base64MasterKey);
            log.info("SecureCredentialStore initialized with provided master key");
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize SecureCredentialStore", e);
        }
    }

    /**
     * Securely stores a user's password.
     *
     * @param sessionId session ID (unique identifier)
     * @param username username
     * @param password password (char array, zeroed after use)
     */
    public void storeCredential(String sessionId, String username, char[] password) {
        if (sessionId == null || username == null || password == null) {
            throw new IllegalArgumentException("sessionId, username, and password cannot be null");
        }

        byte[] passwordBytes = null;
        try {
            // Generate random IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            // Encrypt password
            passwordBytes = charArrayToBytes(password);
            byte[] encryptedPassword = encrypt(passwordBytes, iv);

            // Store encrypted credential
            String key = generateKey(sessionId, username);
            long expiryTime = Instant.now().plusSeconds(EXPIRY_MINUTES * 60).toEpochMilli();

            credentialStore.put(key, new EncryptedCredential(
                encryptedPassword,
                iv,
                username,
                expiryTime
            ));

        } catch (Exception e) {
            throw new RuntimeException("Failed to store credential", e);
        } finally {
            if (passwordBytes != null) {
                Arrays.fill(passwordBytes, (byte) 0);
            }
            Arrays.fill(password, '\0');
        }
    }

    /**
     * Retrieves a user's password (decrypted).
     *
     * @param sessionId session ID
     * @param username username
     * @return decrypted password (Optional); caller must zero after use
     */
    public Optional<char[]> retrievePassword(String sessionId, String username) {
        if (sessionId == null || username == null) {
            return Optional.empty();
        }

        String key = generateKey(sessionId, username);
        EncryptedCredential credential = credentialStore.get(key);

        if (credential == null) {
            return Optional.empty();
        }

        // Check expiry
        if (System.currentTimeMillis() > credential.expiryTime) {
            credentialStore.remove(key);
            return Optional.empty();
        }

        try {
            // Decrypt password
            byte[] decryptedBytes = decrypt(credential.encryptedPassword, credential.iv);
            char[] password = bytesToCharArray(decryptedBytes);

            // Zero out decrypted byte array
            Arrays.fill(decryptedBytes, (byte) 0);

            return Optional.of(password);

        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve credential", e);
        }
    }

    /**
     * Retrieves a user's password using username only.
     *
     * Used as a fallback when session ID is unavailable on async tool threads.
     * If multiple active credentials exist for the same user, the newest one
     * (largest expiry timestamp) is returned.
     */
    public Optional<char[]> retrievePasswordForUser(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }

        long now = System.currentTimeMillis();
        EncryptedCredential selected = null;
        for (Map.Entry<String, EncryptedCredential> entry : credentialStore.entrySet()) {
            EncryptedCredential credential = entry.getValue();
            if (credential == null) continue;
            if (!username.equals(credential.username)) continue;
            if (now > credential.expiryTime) {
                credentialStore.remove(entry.getKey(), credential);
                continue;
            }
            if (selected == null || credential.expiryTime > selected.expiryTime) {
                selected = credential;
            }
        }

        if (selected == null) {
            return Optional.empty();
        }

        try {
            byte[] decryptedBytes = decrypt(selected.encryptedPassword, selected.iv);
            char[] password = bytesToCharArray(decryptedBytes);
            Arrays.fill(decryptedBytes, (byte) 0);
            return Optional.of(password);
        } catch (Exception e) {
            throw new RuntimeException("Failed to retrieve credential by username", e);
        }
    }

    /**
     * Removes credentials (called on logout).
     *
     * @param sessionId session ID
     */
    public void removeCredential(String sessionId) {
        if (sessionId == null) {
            return;
        }

        // Remove all credentials for this session
        credentialStore.entrySet().removeIf(entry ->
            entry.getKey().startsWith(sessionId + ":")
        );
    }

    /**
     * Clears all credentials for a specific user.
     *
     * @param username username
     */
    public void clearUserCredentials(String username) {
        if (username == null) {
            return;
        }

        credentialStore.entrySet().removeIf(entry ->
            username.equals(entry.getValue().username)
        );
    }

    /**
     * Scheduled cleanup of expired credentials (runs every minute).
     */
    @Scheduled(fixedRate = 60000)
    public void cleanExpiredCredentials() {
        long currentTime = System.currentTimeMillis();
        int removedCount = 0;

        var iterator = credentialStore.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (currentTime > entry.getValue().expiryTime) {
                iterator.remove();
                removedCount++;
            }
        }

        if (removedCount > 0) {
            log.info("Cleaned {} expired credentials", removedCount);
        }
    }

    /**
     * Encrypts data.
     */
    private byte[] encrypt(byte[] plaintext, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.ENCRYPT_MODE, masterKey, spec);
        return cipher.doFinal(plaintext);
    }

    /**
     * Decrypts data.
     */
    private byte[] decrypt(byte[] ciphertext, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
        cipher.init(Cipher.DECRYPT_MODE, masterKey, spec);
        return cipher.doFinal(ciphertext);
    }

    private SecretKey parseMasterKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("Master key cannot be null or blank");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Key);
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("CREDENTIAL_STORE_KEY must be 32 bytes (Base64-encoded AES-256 key)");
        }
        return new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * Generates the storage key.
     */
    private String generateKey(String sessionId, String username) {
        return sessionId + ":" + username;
    }

    /**
     * Converts char[] to byte[] using UTF-8 encoding.
     * Correctly handles non-ASCII characters (e.g., CJK passwords).
     */
    private byte[] charArrayToBytes(char[] chars) {
        java.nio.ByteBuffer buf = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    /**
     * Converts byte[] to char[] using UTF-8 decoding.
     */
    private char[] bytesToCharArray(byte[] bytes) {
        java.nio.CharBuffer buf = StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes));
        char[] chars = new char[buf.remaining()];
        buf.get(chars);
        return chars;
    }

    /**
     * Encrypted credential data structure.
     */
    private static class EncryptedCredential {
        final byte[] encryptedPassword;
        final byte[] iv;
        final String username;
        final long expiryTime;

        EncryptedCredential(byte[] encryptedPassword, byte[] iv, String username, long expiryTime) {
            this.encryptedPassword = encryptedPassword;
            this.iv = iv;
            this.username = username;
            this.expiryTime = expiryTime;
        }
    }

    /**
     * Returns the number of stored credentials (for monitoring).
     */
    public int getCredentialCount() {
        return credentialStore.size();
    }
}
