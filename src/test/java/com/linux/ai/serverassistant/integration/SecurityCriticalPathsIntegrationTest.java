package com.linux.ai.serverassistant.integration;

import com.linux.ai.serverassistant.config.AiToolsConfig;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityCriticalPathsIntegrationTest {

    @Autowired
    private UserContext userContext;

    @Autowired
    @Qualifier("listDirectory")
    private Function<AiToolsConfig.ListDirectoryRequest, String> listDirectoryTool;

    @Autowired
    @Qualifier("readFileContent")
    private Function<AiToolsConfig.ReadFileRequest, String> readFileContentTool;

    @Autowired
    @Qualifier("manageUsers")
    private Function<AiToolsConfig.UserRequest, String> manageUsersTool;

    @MockBean
    private AdminAuthorizationService adminAuthorizationService;

    @BeforeEach
    void setUp() {
        when(adminAuthorizationService.isAdmin(anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        userContext.clearCurrentContextKey();
        userContext.clearAllActiveSessions();
        userContext.shutdownCleanupScheduler();
    }

    @Test
    void listDirectory_crossThreadContextKey_shouldResolveActor(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "hello");

        String contextKey = bindActor("rootadmin");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = executor.submit(() ->
                    listDirectoryTool.apply(new AiToolsConfig.ListDirectoryRequest(tempDir.toString(), contextKey)));

            String result = future.get(3, TimeUnit.SECONDS);
            assertFalse(result.contains("SECURITY_VIOLATION"));
            assertTrue(result.contains("Directory:"));
            assertTrue(result.contains("hello.txt"));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void readFileContent_symlinkEscapeViaAllowedPrefix_shouldBeBlocked() throws IOException {
        String contextKey = bindActor("rootadmin");
        Path symlink = Path.of("/tmp/integration-read-escape-link-" + System.nanoTime());
        try {
            try {
                Files.createSymbolicLink(symlink, Path.of("/etc"));
            } catch (UnsupportedOperationException | IOException e) {
                Assumptions.assumeTrue(false, "symlink not supported in test environment");
            }

            String result = readFileContentTool.apply(
                    new AiToolsConfig.ReadFileRequest(symlink.resolve("passwd").toString(), contextKey));

            assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內"));
        } finally {
            Files.deleteIfExists(symlink);
        }
    }

    @Test
    void manageUsers_unicodeUsernameInjection_shouldBeRejectedBeforeCommandExecution() {
        String contextKey = bindActor("rootadmin");
        String unicodeInjectedUsername = "adm\u200Bin";

        String result = manageUsersTool.apply(
                new AiToolsConfig.UserRequest("add", unicodeInjectedUsername, "pass123".toCharArray(), false, contextKey));

        assertTrue(result.contains("使用者名稱格式不合法"));
        assertFalse(result.contains("[CMD:::"));
    }

    private String bindActor(String username) {
        String contextKey = "ctx-" + username + "-" + System.nanoTime();
        userContext.registerToolSession(contextKey, username, "sid-" + username);
        return contextKey;
    }
}
