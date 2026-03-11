package com.linux.ai.serverassistant.service.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OffloadExecutionSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void executeOffload_withNullPathInputs_shouldRejectImmediately() {
        String out = OffloadExecutionSupport.executeOffload(
                null,
                tempDir.resolve("target"),
                (cmd, timeout) -> new OffloadExecutionSupport.OffloadCommandResult(true, "ok"));

        assertTrue(out.contains("來源或目標路徑為空"));
    }

    @Test
    void executeOffload_withNullRunner_shouldRejectImmediately() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("src"));
        Path target = Files.createDirectory(tempDir.resolve("target"));

        String out = OffloadExecutionSupport.executeOffload(source, target, null);

        assertTrue(out.contains("命令執行器未初始化"));
    }

    @Test
    void executeOffload_whenSourceDoesNotExist_shouldNotInvokeRunner() throws Exception {
        Path source = tempDir.resolve("missing");
        Path target = Files.createDirectory(tempDir.resolve("target"));
        AtomicInteger calls = new AtomicInteger();

        String out = OffloadExecutionSupport.executeOffload(source, target, (cmd, timeout) -> {
            calls.incrementAndGet();
            return new OffloadExecutionSupport.OffloadCommandResult(true, "ok");
        });

        assertTrue(out.contains("來源不存在"));
        assertEquals(0, calls.get());
    }

    @Test
    void executeOffload_whenDestinationAlreadyExists_shouldReject() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("src"));
        Path target = Files.createDirectory(tempDir.resolve("target"));
        Files.createDirectory(target.resolve(source.getFileName()));

        String out = OffloadExecutionSupport.executeOffload(
                source,
                target,
                (cmd, timeout) -> new OffloadExecutionSupport.OffloadCommandResult(true, "ok"));

        assertTrue(out.contains("目標位置已存在同名資料夾"));
    }

    @Test
    void executeOffload_whenCopyFails_shouldCleanupReservedDestination() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(source.resolve("a.txt"), "content");
        Path target = Files.createDirectory(tempDir.resolve("target"));

        String out = OffloadExecutionSupport.executeOffload(
                source,
                target,
                (cmd, timeout) -> new OffloadExecutionSupport.OffloadCommandResult(false, "copy failed"));

        Path destination = target.resolve(source.getFileName());
        assertTrue(out.contains("複製失敗"));
        assertFalse(Files.exists(destination));
        assertTrue(Files.exists(source));
        assertFalse(Files.isSymbolicLink(source));
    }

    @Test
    void executeOffload_whenRsyncUnavailable_shouldReturnSpecificError() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("src"));
        Path target = Files.createDirectory(tempDir.resolve("target"));

        String out = OffloadExecutionSupport.executeOffload(
                source,
                target,
                (cmd, timeout) -> new OffloadExecutionSupport.OffloadCommandResult(false, "rsync: command not found"));

        assertTrue(out.contains("無 rsync"));
    }

    @Test
    void executeOffload_whenCopySucceeds_shouldReplaceSourceWithSymlink() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(source.resolve("a.txt"), "content");
        Path target = Files.createDirectory(tempDir.resolve("target"));

        String out = OffloadExecutionSupport.executeOffload(
                source,
                target,
                (cmd, timeout) -> new OffloadExecutionSupport.OffloadCommandResult(true, "copied"));

        Path destination = target.resolve(source.getFileName());
        assertTrue(out.contains("✅ Offload 完成"));
        assertTrue(Files.isDirectory(destination));
        assertTrue(Files.isSymbolicLink(source));
        assertEquals(destination, Files.readSymbolicLink(source));
    }

    @Test
    void executeOffload_withRealisticCopyRunner_shouldPreserveNestedFileContents() throws Exception {
        Path source = Files.createDirectory(tempDir.resolve("project"));
        Path nested = Files.createDirectories(source.resolve("dir/sub"));
        Files.writeString(source.resolve("root.txt"), "root-data");
        Files.writeString(nested.resolve("nested.txt"), "nested-data");

        Path target = Files.createDirectory(tempDir.resolve("archive"));

        String out = OffloadExecutionSupport.executeOffload(
                source,
                target,
                (cmd, timeout) -> emulateRsyncCopy(cmd));

        Path destination = target.resolve("project");
        assertTrue(out.contains("✅ Offload 完成"));
        assertTrue(Files.isDirectory(destination));
        assertEquals("root-data", Files.readString(destination.resolve("root.txt")));
        assertEquals("nested-data", Files.readString(destination.resolve("dir/sub/nested.txt")));
        assertTrue(Files.isSymbolicLink(source));
        assertEquals(destination, Files.readSymbolicLink(source));
    }

    @Test
    void extractSubMountPointsFromMountInfo_shouldDecodeAndFilterSubMounts() {
        List<String> mountInfo = List.of(
                "11 1 8:1 / /mnt/data rw,relatime - ext4 /dev/sda1 rw",
                "12 11 8:2 / /mnt/data/sub\\040space rw,relatime - ext4 /dev/sdb1 rw",
                "13 11 8:3 / /mnt/data/sub2 rw,relatime - ext4 /dev/sdb2 rw",
                "14 11 8:4 / /other rw,relatime - ext4 /dev/sdb3 rw",
                "broken-line"
        );

        List<Path> subMounts = OffloadExecutionSupport.extractSubMountPointsFromMountInfo(Path.of("/mnt/data"), mountInfo);

        assertEquals(List.of(Path.of("/mnt/data/sub space"), Path.of("/mnt/data/sub2")), subMounts);
    }

    @Test
    void extractSubMountPointsFromMountInfo_whenSourceIsRoot_shouldIncludeAllAbsoluteMounts() {
        List<String> mountInfo = List.of(
                "11 1 8:1 / / rw,relatime - ext4 /dev/sda1 rw",
                "12 11 8:2 / /proc rw,relatime - proc proc rw",
                "13 11 8:3 / /tmp rw,relatime - tmpfs tmpfs rw"
        );

        List<Path> subMounts = OffloadExecutionSupport.extractSubMountPointsFromMountInfo(Path.of("/"), mountInfo);

        assertEquals(List.of(Path.of("/proc"), Path.of("/tmp")), subMounts);
    }

    @Test
    void formatSubMountBlockedMessage_shouldIncludeHintLimitAndOmittedCount() {
        List<Path> subMounts = List.of(
                Path.of("/src/a"),
                Path.of("/src/b"),
                Path.of("/src/c")
        );

        String message = OffloadExecutionSupport.formatSubMountBlockedMessage(Path.of("/src"), subMounts, 1);

        assertTrue(message.contains("顯示前 1 筆"));
        assertTrue(message.contains("`/src/a`"));
        assertTrue(message.contains("其餘 2 筆已省略"));
    }

    @Test
    void detectSubMountPoints_withRelativeSource_shouldReturnEmptyList() {
        List<Path> subMounts = OffloadExecutionSupport.detectSubMountPoints(Path.of("relative/source"));
        assertTrue(subMounts.isEmpty());
    }

    private OffloadExecutionSupport.OffloadCommandResult emulateRsyncCopy(List<String> command) {
        if (command == null || command.size() < 5 || !"rsync".equals(command.get(0))) {
            return new OffloadExecutionSupport.OffloadCommandResult(false, "unexpected command");
        }

        String sourceText = command.get(command.size() - 2);
        String destinationText = command.get(command.size() - 1);
        Path sourcePath = Path.of(stripTrailingSlash(sourceText));
        Path destinationPath = Path.of(stripTrailingSlash(destinationText));

        try {
            copyDirectoryContents(sourcePath, destinationPath);
            return new OffloadExecutionSupport.OffloadCommandResult(true, "copied");
        } catch (IOException e) {
            return new OffloadExecutionSupport.OffloadCommandResult(false, e.getMessage());
        }
    }

    private void copyDirectoryContents(Path source, Path destination) throws IOException {
        if (!Files.exists(destination)) {
            Files.createDirectories(destination);
        }
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                if (relative.toString().isEmpty()) {
                    continue;
                }
                Path targetPath = destination.resolve(relative);
                if (Files.isDirectory(path)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private String stripTrailingSlash(String text) {
        if (text == null || text.isEmpty()) return "";
        if (text.endsWith("/")) return text.substring(0, text.length() - 1);
        return text;
    }
}
