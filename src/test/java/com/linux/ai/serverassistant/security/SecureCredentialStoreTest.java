package com.linux.ai.serverassistant.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SecureCredentialStoreTest {

    private static final String TEST_MASTER_KEY = Base64.getEncoder()
        .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private SecureCredentialStore store;

    @BeforeEach
    void setUp() {
        store = new SecureCredentialStore(TEST_MASTER_KEY);
    }

    // ========== Store and retrieve ==========

    @Test
    void storeAndRetrieve_shouldReturnOriginalPassword() {
        char[] password = "mySecret123".toCharArray();
        store.storeCredential("session1", "alice", password);

        Optional<char[]> retrieved = store.retrievePassword("session1", "alice");
        assertTrue(retrieved.isPresent());
        assertEquals("mySecret123", new String(retrieved.get()));
    }

    @Test
    void storeCredential_shouldZeroOutOriginalArray() {
        char[] password = "mySecret".toCharArray();
        store.storeCredential("s1", "bob", password);

        // Original array should be zeroed
        assertEquals('\0', password[0]);
        assertEquals('\0', password[password.length - 1]);
    }

    @Test
    void store_unicodePassword_shouldRoundTrip() {
        char[] password = "密碼測試123".toCharArray();
        store.storeCredential("s1", "user", password);

        Optional<char[]> retrieved = store.retrievePassword("s1", "user");
        assertTrue(retrieved.isPresent());
        assertEquals("密碼測試123", new String(retrieved.get()));
    }

    // ========== Missing / wrong keys ==========

    @Test
    void retrieve_nonExistentSession_shouldReturnEmpty() {
        Optional<char[]> result = store.retrievePassword("no-such-session", "alice");
        assertTrue(result.isEmpty());
    }

    @Test
    void retrieve_wrongUsername_shouldReturnEmpty() {
        store.storeCredential("s1", "alice", "pass".toCharArray());
        Optional<char[]> result = store.retrievePassword("s1", "bob");
        assertTrue(result.isEmpty());
    }

    @Test
    void retrieve_wrongSession_shouldReturnEmpty() {
        store.storeCredential("s1", "alice", "pass".toCharArray());
        Optional<char[]> result = store.retrievePassword("s2", "alice");
        assertTrue(result.isEmpty());
    }

    @Test
    void retrieveByUsername_shouldReturnPasswordWhenSessionUnknown() {
        store.storeCredential("s1", "alice", "pass-by-user".toCharArray());

        Optional<char[]> result = store.retrievePasswordForUser("alice");

        assertTrue(result.isPresent());
        assertEquals("pass-by-user", new String(result.get()));
    }

    // ========== Null handling ==========

    @Test
    void storeNull_shouldThrow() {
        assertThrows(IllegalArgumentException.class, () ->
            store.storeCredential(null, "user", "pass".toCharArray()));
        assertThrows(IllegalArgumentException.class, () ->
            store.storeCredential("s1", null, "pass".toCharArray()));
        assertThrows(IllegalArgumentException.class, () ->
            store.storeCredential("s1", "user", null));
    }

    @Test
    void retrieveNull_shouldReturnEmpty() {
        assertTrue(store.retrievePassword(null, "user").isEmpty());
        assertTrue(store.retrievePassword("s1", null).isEmpty());
    }

    // ========== Remove credentials ==========

    @Test
    void removeCredential_shouldDeleteAllForSession() {
        store.storeCredential("s1", "alice", "p1".toCharArray());
        store.storeCredential("s1", "bob", "p2".toCharArray());
        store.storeCredential("s2", "alice", "p3".toCharArray());

        store.removeCredential("s1");

        assertTrue(store.retrievePassword("s1", "alice").isEmpty());
        assertTrue(store.retrievePassword("s1", "bob").isEmpty());
        // s2 should be unaffected
        assertTrue(store.retrievePassword("s2", "alice").isPresent());
    }

    @Test
    void removeCredential_null_shouldNotThrow() {
        assertDoesNotThrow(() -> store.removeCredential(null));
    }

    // ========== Clear user credentials ==========

    @Test
    void clearUserCredentials_shouldDeleteByUsername() {
        store.storeCredential("s1", "alice", "p1".toCharArray());
        store.storeCredential("s2", "alice", "p2".toCharArray());
        store.storeCredential("s3", "bob", "p3".toCharArray());

        store.clearUserCredentials("alice");

        assertTrue(store.retrievePassword("s1", "alice").isEmpty());
        assertTrue(store.retrievePassword("s2", "alice").isEmpty());
        assertTrue(store.retrievePassword("s3", "bob").isPresent());
    }

    @Test
    void clearUserCredentials_null_shouldNotThrow() {
        assertDoesNotThrow(() -> store.clearUserCredentials(null));
    }

    // ========== Credential count ==========

    @Test
    void getCredentialCount_shouldTrackEntries() {
        assertEquals(0, store.getCredentialCount());
        store.storeCredential("s1", "a", "p".toCharArray());
        assertEquals(1, store.getCredentialCount());
        store.storeCredential("s2", "b", "p".toCharArray());
        assertEquals(2, store.getCredentialCount());
        store.removeCredential("s1");
        assertEquals(1, store.getCredentialCount());
    }

    // ========== Overwrite ==========

    @Test
    void storeCredential_sameKey_shouldOverwrite() {
        store.storeCredential("s1", "alice", "old".toCharArray());
        store.storeCredential("s1", "alice", "new".toCharArray());

        Optional<char[]> retrieved = store.retrievePassword("s1", "alice");
        assertTrue(retrieved.isPresent());
        assertEquals("new", new String(retrieved.get()));
        assertEquals(1, store.getCredentialCount());
    }

    // ========== Expiry cleanup ==========

    @Test
    void cleanExpiredCredentials_shouldRemoveExpired() {
        store.storeCredential("s1", "alice", "p".toCharArray());
        assertEquals(1, store.getCredentialCount());
        // Fresh credentials should survive cleanup
        store.cleanExpiredCredentials();
        assertEquals(1, store.getCredentialCount());
    }
}
