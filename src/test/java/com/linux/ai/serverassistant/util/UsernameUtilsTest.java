package com.linux.ai.serverassistant.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsernameUtilsTest {

    @Test
    void normalizeUsernameOrAnonymous_shouldReturnAnonymousForNull() {
        assertEquals("anonymous", UsernameUtils.normalizeUsernameOrAnonymous(null));
    }

    @Test
    void normalizeUsernameOrAnonymous_shouldReturnAnonymousForBlank() {
        assertEquals("anonymous", UsernameUtils.normalizeUsernameOrAnonymous("   "));
    }

    @Test
    void normalizeUsernameOrAnonymous_shouldTrimUsername() {
        assertEquals("alice", UsernameUtils.normalizeUsernameOrAnonymous("  alice  "));
    }
}
