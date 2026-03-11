package com.linux.ai.serverassistant.service.system;

import com.linux.ai.serverassistant.entity.CommandLog;
import com.linux.ai.serverassistant.repository.CommandLogRepository;
import com.linux.ai.serverassistant.util.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DiskMountServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void upsertFstabEntry_ext4_shouldReplaceExistingTargetAndUsePassNo2() throws Exception {
        Path fstab = tempDir.resolve("fstab");
        String original = """
                # sample
                UUID=old-uuid /mnt/train-data-1-hdd ext4 defaults 0 2
                """;
        Files.writeString(fstab, original);

        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), fstab, new UserContext());
        DiskMountService.FstabUpdateResult update =
                service.upsertFstabEntry(Path.of("/mnt/train-data-1-hdd"), "new-uuid", "ext4", "defaults,nofail");

        String updated = Files.readString(fstab);
        assertTrue(updated.contains("UUID=new-uuid /mnt/train-data-1-hdd ext4 defaults,nofail 0 2"));
        assertFalse(updated.contains("UUID=old-uuid /mnt/train-data-1-hdd"));
        assertTrue(Files.exists(update.backupPath()));
        assertEquals(original, Files.readString(update.backupPath()));
    }

    @Test
    void upsertFstabEntry_nonExtFs_shouldUsePassNo0() throws Exception {
        Path fstab = tempDir.resolve("fstab");
        Files.writeString(fstab, "# empty\n");

        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), fstab, new UserContext());
        DiskMountService.FstabUpdateResult update =
                service.upsertFstabEntry(Path.of("/mnt/xfs-data"), "uuid-xfs", "xfs", "defaults,nofail");

        assertTrue(update.entry().endsWith(" 0 0"));
        assertTrue(Files.readString(fstab).contains("UUID=uuid-xfs /mnt/xfs-data xfs defaults,nofail 0 0"));
    }

    @Test
    void restoreFstabFromBackup_shouldRestorePreviousFile() throws Exception {
        Path fstab = tempDir.resolve("fstab");
        String original = "UUID=before /mnt/data ext4 defaults 0 2\n";
        Files.writeString(fstab, original);

        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), fstab, new UserContext());
        DiskMountService.FstabUpdateResult update =
                service.upsertFstabEntry(Path.of("/mnt/data"), "after-uuid", "ext4", "defaults,nofail");

        Files.writeString(fstab, "BROKEN\n");
        boolean restored = service.restoreFstabFromBackup(update.backupPath());

        assertTrue(restored);
        assertEquals(original, Files.readString(fstab));
    }

    @Test
    void validateDiskDeviceKind_shouldOnlyAllowDiskType() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());

        String disk = service.validateDiskDeviceKind(Path.of("/dev/sdc"), "disk", "`/mount`");
        String part = service.validateDiskDeviceKind(Path.of("/dev/sdc1"), "part", "`/mount`");
        String lvm = service.validateDiskDeviceKind(Path.of("/dev/mapper/vg-data"), "lvm", "`/mount`");
        String unknown = service.validateDiskDeviceKind(Path.of("/dev/sdc"), null, "`/mount`");

        assertNull(disk);
        assertTrue(part.contains("僅允許整顆磁碟"));
        assertTrue(lvm.contains("僅允許整顆磁碟"));
        assertTrue(unknown.contains("無法判斷裝置類型"));
    }

    @Test
    void findMountedChildFromLsblkOutput_shouldDetectMountedPartition() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());
        String output = """
                /dev/sdc -
                /dev/sdc1 /mnt/data
                /dev/sdc2 -
                """;
        String mounted = service.findMountedChildFromLsblkOutput(Path.of("/dev/sdc"), output);
        assertEquals("/dev/sdc1 -> /mnt/data", mounted);
    }

    @Test
    void findMountedChildFromLsblkOutput_shouldIgnoreRootDiskMountpoint() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());
        String output = "/dev/sdc /mnt/raw-disk";
        String mounted = service.findMountedChildFromLsblkOutput(Path.of("/dev/sdc"), output);
        assertNull(mounted);
    }

    @Test
    void validateMountedChildCheckResult_lsblkFailure_shouldReject() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());
        String error = service.validateMountedChildCheckResult(Path.of("/dev/sdc"), 32, "lsblk failed");
        assertTrue(error.contains("lsblk 執行失敗"));
    }

    @Test
    void validateMountedChildCheckResult_blankOutput_shouldReject() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());
        String error = service.validateMountedChildCheckResult(Path.of("/dev/sdc"), 0, " ");
        assertTrue(error.contains("lsblk 無輸出"));
    }

    @Test
    void validateFindmntProbeResult_noMatch_shouldPass() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());
        String error = service.validateFindmntProbeResult(1, "", "mounted", "probe failed");
        assertNull(error);
    }

    @Test
    void validateFindmntProbeResult_probeFailure_shouldReject() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());
        String error = service.validateFindmntProbeResult(32, "", "mounted", "probe failed");
        assertTrue(error.contains("probe failed"));
        assertTrue(error.contains("Exit Code: 32"));
    }

    @Test
    void validateFindmntProbeResult_outputPresent_shouldTreatAsMounted() {
        DiskMountService service = new DiskMountService(mock(CommandLogRepository.class), tempDir.resolve("fstab"), new UserContext());
        String error = service.validateFindmntProbeResult(0, "/dev/sdc /mnt/data", "mounted", "probe failed");
        assertEquals("mounted", error);
    }

    @Test
    void executeConfirmedMount_validationFailure_shouldWriteFailedAuditLog() {
        CommandLogRepository repo = mock(CommandLogRepository.class);
        DiskMountService service = new DiskMountService(repo, tempDir.resolve("fstab"), new UserContext());

        String result = service.executeConfirmedMount(
                Path.of("/dev/null"),
                Path.of("/tmp"),
                "ext4",
                "defaults,nofail"
        );

        assertTrue(result.contains("[SECURITY_VIOLATION]"));
        ArgumentCaptor<CommandLog> captor = ArgumentCaptor.forClass(CommandLog.class);
        verify(repo).save(captor.capture());
        CommandLog log = captor.getValue();
        assertFalse(log.isSuccess());
        assertEquals("mount /dev/null /tmp ext4 defaults,nofail", log.getCommand());
    }
}
