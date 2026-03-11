package com.linux.ai.serverassistant.service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthorizationServiceTest {

    private final AdminAuthorizationService service = new AdminAuthorizationService();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "\t"})
    void isAdmin_shouldReturnFalse_forNullOrBlank(String username) {
        assertFalse(service.isAdmin(username));
    }

    @Test
    void isAdmin_shouldReturnFalse_forNonexistentUser() {
        assertFalse(service.isAdmin("nonexistent_user_xyz_12345"));
    }

    @Test
    void isAdmin_shouldReturnConsistentResults_withCache() {
        String user = "nonexistent_user_xyz_12345";
        boolean first = service.isAdmin(user);
        boolean second = service.isAdmin(user);
        assertEquals(first, second);
    }
}
