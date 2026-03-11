package com.linux.ai.serverassistant.service.file;

import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.service.security.AdminAuthorizationService;
import com.linux.ai.serverassistant.service.security.FileOperationRateLimiter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class FileOperationServiceTest {

    private CommandLogRepository commandLogRepository;
    private AdminAuthorizationService adminAuthorizationService;
    private FileOperationRateLimiter fileOperationRateLimiter;
    private FileOperationService service;

    @BeforeEach
    void setUp() {
        commandLogRepository = mock(CommandLogRepository.class);
        adminAuthorizationService = mock(AdminAuthorizationService.class);
        fileOperationRateLimiter = mock(FileOperationRateLimiter.class);
        when(adminAuthorizationService.isAdmin(anyString())).thenReturn(false);
        when(adminAuthorizationService.isAdmin("admin")).thenReturn(true);
        when(fileOperationRateLimiter.tryConsume(anyString())).thenReturn(0L);
        service = new FileOperationService(commandLogRepository, adminAuthorizationService, fileOperationRateLimiter);
    }

    // ========== Path validation ==========

    @Nested
    class PathValidation {

        @ParameterizedTest
        @ValueSource(strings = {
            "/etc/shadow",
            "/root/.bashrc",
            "/bin/ls",
            "/usr/bin/python3",
            "/sys/class/net"
        })
        void readFromDisallowedPath_shouldBeBlocked(String path) {
            String result = service.readFileContent(path, "admin");
            assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內"),
                "Should block read from: " + path);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/var/log/test.log",
            "/etc/nginx/nginx.conf",
            "/etc/apache2/apache2.conf"
        })
        void readFromAllowedPath_shouldNotBeBlockedByPathCheck(String path) {
            // These paths are allowed for reading but the files may not exist
            String result = service.readFileContent(path, "admin");
            // Should not be SECURITY_VIOLATION — may be "file not found" instead
            assertFalse(result.contains("SECURITY_VIOLATION"),
                "Should allow read path check for: " + path);
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "/var/log/test.log",
            "/etc/nginx/test.conf",
            "/usr/local/bin/test"
        })
        void writeToReadOnlyPath_shouldBeBlocked(String path) {
            String result = service.writeFileContent(path, "test", "admin");
            assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內"),
                "Should block write to read-only path: " + path);
        }

        @Test
        void nonAdminReadOutsideOwnHome_shouldBeBlocked() {
            String result = service.readFileContent("/tmp/test.txt", "alice");
            assertTrue(result.contains("SECURITY_VIOLATION"));
            assertTrue(result.contains("家目錄"));
        }

        @Test
        void readWithoutResolvedActor_shouldBeBlocked() {
            String result = service.readFileContent("/home/alice/test.txt", null);
            assertTrue(result.contains("SECURITY_VIOLATION"));
            assertTrue(result.contains("無法識別目前操作使用者"));
        }

        @Test
        void writeWithInvalidActorUsername_shouldBeBlocked() {
            String result = service.writeFileContent("/home/alice/test.txt", "data", "../alice");
            assertTrue(result.contains("SECURITY_VIOLATION"));
            assertTrue(result.contains("無法識別目前操作使用者"));
        }

        @Test
        void readThroughAllowedPrefixSymlinkToDisallowedPath_shouldBeBlocked() throws IOException {
            Path symlink = Path.of("/tmp/read-escape-link-" + System.nanoTime());
            try {
                try {
                    Files.createSymbolicLink(symlink, Path.of("/etc"));
                } catch (UnsupportedOperationException | IOException e) {
                    Assumptions.assumeTrue(false, "symlink not supported in test environment");
                }

                String result = service.readFileContent(symlink.resolve("passwd").toString(), "admin");
                assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內") || result.contains("符號連結"));
            } finally {
                Files.deleteIfExists(symlink);
            }
        }

        @Test
        void listDirectoryThroughAllowedPrefixSymlinkToDisallowedPath_shouldBeBlocked() throws IOException {
            Path symlink = Path.of("/tmp/list-escape-link-" + System.nanoTime());
            try {
                try {
                    Files.createSymbolicLink(symlink, Path.of("/etc"));
                } catch (UnsupportedOperationException | IOException e) {
                    Assumptions.assumeTrue(false, "symlink not supported in test environment");
                }

                String result = service.listDirectory(symlink.toString(), "admin");
                assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內") || result.contains("符號連結"));
            } finally {
                Files.deleteIfExists(symlink);
            }
        }

        @Test
        void writeThroughAllowedPrefixSymlinkToDisallowedPath_shouldBeBlocked() throws IOException {
            Path symlink = Path.of("/tmp/write-escape-link-" + System.nanoTime());
            try {
                try {
                    Files.createSymbolicLink(symlink, Path.of("/var/log"));
                } catch (UnsupportedOperationException | IOException e) {
                    Assumptions.assumeTrue(false, "symlink not supported in test environment");
                }

                String result = service.writeFileContent(symlink.resolve("escape-test.log").toString(), "blocked", "admin");
                assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內") || result.contains("符號連結"));
            } finally {
                Files.deleteIfExists(symlink);
            }
        }
    }

    // ========== listDirectory ==========

    @Nested
    class ListDirectory {

        @Test
        void listDirectory_blockedPath_shouldReturnSecurityViolation() {
            String result = service.listDirectory("/root", "admin");
            assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內"));
        }

        @Test
        void listDirectory_nonExistentPath_shouldReturnError(@TempDir Path tempDir) {
            String result = service.listDirectory(tempDir.resolve("nonexistent").toString(), "admin");
            assertTrue(result.contains("不存在"));
        }

        @Test
        void listDirectory_rateLimitExceeded_shouldReturnRateLimitMessage(@TempDir Path tempDir) {
            when(fileOperationRateLimiter.tryConsume("admin")).thenReturn(5L);
            String result = service.listDirectory(tempDir.toString(), "admin");
            assertTrue(result.contains("過於頻繁"));
            assertTrue(result.contains("5"));
        }
    }

    // ========== readFileContent ==========

    @Nested
    class ReadFileContent {

        @Test
        void readNonExistentFile_shouldReturnError() {
            String result = service.readFileContent("/tmp/nonexistent-file-abc123.txt", "admin");
            assertTrue(result.contains("不存在"));
        }

        @Test
        void readDirectoryAsFile_shouldReturnError() {
            String result = service.readFileContent("/tmp", "admin");
            assertTrue(result.contains("目錄"));
        }

        @Test
        void readExistingFile_shouldReturnContent() throws IOException {
            Path file = Files.createTempFile(Path.of("/tmp"), "test-", ".txt");
            try {
                Files.writeString(file, "hello test content");
                String result = service.readFileContent(file.toString(), "admin");
                assertTrue(result.contains("hello test content"));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Test
        void readLargeFile_shouldRejectOver50KB() throws IOException {
            Path file = Files.createTempFile(Path.of("/tmp"), "large-", ".txt");
            try {
                Files.writeString(file, "x".repeat(51 * 1024));
                String result = service.readFileContent(file.toString(), "admin");
                assertTrue(result.contains("過大"));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Test
        void readFileContent_rateLimitExceeded_shouldReturnRateLimitMessage() throws IOException {
            Path file = Files.createTempFile(Path.of("/tmp"), "rate-limit-", ".txt");
            try {
                Files.writeString(file, "content");
                when(fileOperationRateLimiter.tryConsume("admin")).thenReturn(4L);
                String result = service.readFileContent(file.toString(), "admin");
                assertTrue(result.contains("過於頻繁"));
                assertTrue(result.contains("4"));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Test
        void readSymlinkPathWithinAllowedScope_shouldStillReadTargetContent() throws IOException {
            Path realTarget = Files.createTempFile(Path.of("/tmp"), "read-real-", ".txt");
            Path symlinkPath = realTarget.resolveSibling("read-link-" + System.nanoTime() + ".txt");
            try {
                Files.writeString(realTarget, "content via symlink");
                try {
                    Files.createSymbolicLink(symlinkPath, realTarget);
                } catch (UnsupportedOperationException | IOException e) {
                    Assumptions.assumeTrue(false, "symlink not supported in test environment");
                }

                String result = service.readFileContent(symlinkPath.toString(), "admin");
                assertTrue(result.contains("content via symlink"));
            } finally {
                Files.deleteIfExists(symlinkPath);
                Files.deleteIfExists(realTarget);
            }
        }
    }

    // ========== writeFileContent ==========

    @Nested
    class WriteFileContent {

        @Test
        void writeToAllowedPath_shouldSucceed() throws IOException {
            Path file = Path.of("/tmp/test-write-" + System.nanoTime() + ".txt");
            try {
                String result = service.writeFileContent(file.toString(), "hello", "admin");
                assertTrue(result.contains("寫入"));
                assertEquals("hello", Files.readString(file));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Test
        void writeOverMaxSize_shouldBeBlocked() {
            String bigContent = "x".repeat(1024 * 1024 + 1);
            String result = service.writeFileContent("/tmp/test.txt", bigContent, "admin");
            assertTrue(result.contains("過大") || result.contains("1MB"));
        }

        @Test
        void writeOverExistingFile_shouldIndicateOverwrite() throws IOException {
            Path file = Files.createTempFile(Path.of("/tmp"), "overwrite-", ".txt");
            try {
                Files.writeString(file, "old");
                String result = service.writeFileContent(file.toString(), "new", "admin");
                assertTrue(result.contains("寫入"));
                assertEquals("new", Files.readString(file));
            } finally {
                Files.deleteIfExists(file);
            }
        }

        @Test
        void writeNewFile_shouldIndicateNewCreation() throws IOException {
            String path = "/tmp/brand-new-" + System.nanoTime() + ".txt";
            try {
                String result = service.writeFileContent(path, "content", "admin");
                assertTrue(result.contains("寫入"));
                assertTrue(Files.exists(Path.of(path)));
                assertEquals("content", Files.readString(Path.of(path)));
            } finally {
                try { Files.deleteIfExists(Path.of(path)); } catch (Exception ignored) {}
            }
        }

        @Test
        void writeToSymlinkFile_shouldBeBlockedAndNotOverwriteTarget() throws IOException {
            Path realTarget = Files.createTempFile(Path.of("/tmp"), "real-target-", ".txt");
            Path symlinkPath = realTarget.resolveSibling("symlink-target-" + System.nanoTime() + ".txt");
            try {
                Files.writeString(realTarget, "original");
                try {
                    Files.createSymbolicLink(symlinkPath, realTarget);
                } catch (UnsupportedOperationException | IOException e) {
                    Assumptions.assumeTrue(false, "symlink not supported in test environment");
                }

                String result = service.writeFileContent(symlinkPath.toString(), "overwritten", "admin");
                assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("符號連結"));
                assertEquals("original", Files.readString(realTarget));
            } finally {
                Files.deleteIfExists(symlinkPath);
                Files.deleteIfExists(realTarget);
            }
        }

        @Test
        void writeNullContent_shouldTreatAsEmptyString() throws IOException {
            Path file = Files.createTempFile(Path.of("/tmp"), "null-content-", ".txt");
            try {
                String result = service.writeFileContent(file.toString(), null, "admin");
                assertTrue(result.contains("寫入"));
                assertEquals("", Files.readString(file));
            } finally {
                Files.deleteIfExists(file);
            }
        }
    }

    // ========== createDirectory ==========

    @Nested
    class CreateDirectory {

        @Test
        void createDirectory_blockedPath_shouldReturnError() {
            String result = service.createDirectory("/etc/test-dir", "admin");
            assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("不在允許範圍內"));
        }

        @Test
        void createDirectory_alreadyExists_shouldReturnError() throws IOException {
            // Use a path inside /tmp that already exists (not /tmp itself, which is a write-blocked prefix root)
            java.nio.file.Path existing = Files.createTempDirectory(Path.of("/tmp"), "existing-");
            try {
                String result = service.createDirectory(existing.toString(), "admin");
                assertTrue(result.contains("已存在"));
            } finally {
                Files.deleteIfExists(existing);
            }
        }

        @Test
        void createDirectory_success() {
            String path = "/tmp/test-dir-" + System.nanoTime();
            try {
                String result = service.createDirectory(path, "admin");
                assertTrue(result.contains("建立"));
                assertTrue(Files.isDirectory(Path.of(path)));
            } finally {
                try { Files.deleteIfExists(Path.of(path)); } catch (Exception ignored) {}
            }
        }

        @Test
        void createDirectory_underSymlinkedAncestor_shouldBeBlocked() throws IOException {
            Path symlink = Path.of("/tmp/create-dir-link-" + System.nanoTime());
            try {
                try {
                    Files.createSymbolicLink(symlink, Path.of("/etc"));
                } catch (UnsupportedOperationException | IOException e) {
                    Assumptions.assumeTrue(false, "symlink not supported in test environment");
                }

                String result = service.createDirectory(symlink.resolve("blocked").toString(), "admin");
                assertTrue(result.contains("SECURITY_VIOLATION") || result.contains("符號連結") || result.contains("不在允許範圍內"));
            } finally {
                Files.deleteIfExists(symlink);
            }
        }
    }

    // ========== Audit logging ==========

    @Test
    void readFile_shouldSaveAuditLog() throws IOException {
        Path file = Files.createTempFile(Path.of("/tmp"), "audit-", ".txt");
        try {
            Files.writeString(file, "content");
            service.readFileContent(file.toString(), "admin");
            verify(commandLogRepository, atLeastOnce()).save(any());
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
