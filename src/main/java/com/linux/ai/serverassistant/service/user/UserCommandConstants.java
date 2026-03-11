package com.linux.ai.serverassistant.service.user;

/**
 * Shared command constants for user-management related read-only operations.
 */
public final class UserCommandConstants {

    private UserCommandConstants() {}

    /**
     * List login-capable local users from /etc/passwd.
     *
     * Rule:
     * - Exclude common non-login shells (nologin/false)
     * - Keep accounts whose shell path points to a bin directory
     */
    public static final String LIST_LOGIN_USERS_COMMAND =
            "cut -d: -f1,7 /etc/passwd "
                    + "| grep -Ev ':(/usr/sbin/nologin|/sbin/nologin|/bin/false|/usr/bin/false)' "
                    + "| grep -E ':(/bin/.*|/sbin/.*|/usr/bin/.*|/usr/sbin/.*|/usr/local/bin/.*)' "
                    + "| cut -d: -f1";
}
