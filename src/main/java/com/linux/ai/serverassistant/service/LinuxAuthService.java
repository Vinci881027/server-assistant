package com.linux.ai.serverassistant.service;

import org.jvnet.libpam.PAM;
import org.jvnet.libpam.PAMException;
import org.springframework.stereotype.Service;

import java.util.Arrays;

@Service
public class LinuxAuthService {

    public boolean authenticate(String username, char[] password) {
        if (username == null || password == null) {
            return false;
        }

        String passwordString = null;
        try {
            // Authenticate Linux user using PAM (Pluggable Authentication Modules)
            // "passwd" or "sshd" are service names, usually corresponding to configuration files under /etc/pam.d/
            // Note: PAM API requires String; passwordString cannot be zeroed after creation (Java limitation).
            // The char[] is zeroed in the finally block to minimize exposure from the caller's copy.
            passwordString = new String(password);
            new PAM("sshd").authenticate(username, passwordString);
            return true;
        } catch (PAMException e) {
            // Authentication failed (incorrect password or user does not exist)
            return false;
        } finally {
            Arrays.fill(password, '\0');
            // Remove reference so the String is eligible for GC sooner.
            passwordString = null;
        }
    }
}
