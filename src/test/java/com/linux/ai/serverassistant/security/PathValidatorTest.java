package com.linux.ai.serverassistant.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PathValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void symlinkToProtectedPath_shouldBeRejected() throws IOException {
        Path symlink = tempDir.resolve("link-to-etc");
        Files.createSymbolicLink(symlink, Path.of("/etc"));

        PathValidator validator = new PathValidator(
                new CommandTokenizer(),
                tempDir,
                Set.of("/", "/etc")
        );

        String protectedHit = validator.checkProtectedPaths("rm -rf " + symlink, 0);
        assertEquals("/etc", protectedHit);
    }

    @Test
    void nonExistingChildUnderSymlinkToProtectedPath_shouldBeRejected() throws IOException {
        Path symlink = tempDir.resolve("link-to-etc");
        Files.createSymbolicLink(symlink, Path.of("/etc"));
        Path nestedNonExisting = symlink.resolve("not-found-child");

        PathValidator validator = new PathValidator(
                new CommandTokenizer(),
                tempDir,
                Set.of("/", "/etc")
        );

        String protectedHit = validator.checkProtectedPaths("rm -rf " + nestedNonExisting, 0);
        assertEquals("/etc", protectedHit);
    }

    @Test
    void homeDescendant_shouldNotBeRejectedByProtectedPathRule() {
        PathValidator validator = new PathValidator(
                new CommandTokenizer(),
                tempDir,
                Set.of("/", "/home")
        );

        String protectedHit = validator.checkProtectedPaths("rm -rf /home/liang/test.txt", 0);
        assertNull(protectedHit);
    }

    @Test
    void homeRoot_shouldStillBeRejected() {
        PathValidator validator = new PathValidator(
                new CommandTokenizer(),
                tempDir,
                Set.of("/", "/home")
        );

        String protectedHit = validator.checkProtectedPaths("rm -rf /home", 0);
        assertEquals("/home", protectedHit);
    }
}
