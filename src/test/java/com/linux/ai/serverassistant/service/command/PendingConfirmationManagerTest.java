package com.linux.ai.serverassistant.service.command;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PendingConfirmationManagerTest {

    @Test
    void getPendingCommandPrompt_shouldReturnCurrentUserCommandOnly() {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(120_000L, 2_000);
        PendingConfirmationManager manager = new PendingConfirmationManager(registry);

        for (int i = 0; i < 200; i++) {
            manager.store(PendingConfirmationScope.CMD, "bob", "cmd-" + i);
        }
        manager.store(PendingConfirmationScope.CMD, "alice", "rm -rf /tmp/demo");

        var prompt = manager.getPendingCommandPrompt("alice");
        assertTrue(prompt.isPresent());
        assertTrue(prompt.get().contains("rm -rf /tmp/demo"));
        assertTrue(manager.getPendingCommandPrompt("alice").isEmpty());
    }

    @Test
    void removeByPrefix_shouldKeepReverseIndexConsistent() {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(120_000L, 2_000);

        registry.put("alice:cmd:rm /tmp/a");
        registry.put("alice:mount:mount-confirm --device /dev/sdc --target /mnt/data --fstype ext4 --options defaults");
        registry.put("bob:cmd:reboot");

        registry.removeByPrefix("alice:cmd:");
        assertFalse(registry.keysForUsername("alice").contains("alice:cmd:rm /tmp/a"));
        assertTrue(registry.keysForUsername("alice").stream().anyMatch(k -> k.startsWith("alice:mount:")));

        registry.removeByPrefix("alice:");
        assertTrue(registry.keysForUsername("alice").isEmpty());
        assertFalse(registry.keysForUsername("bob").isEmpty());
    }

    @Test
    void purgeExpired_shouldAlsoClearReverseIndex() throws Exception {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(1L, 10);
        registry.put("alice:cmd:rm /tmp/old");

        Thread.sleep(5L);
        registry.purgeExpired();

        assertTrue(registry.keysForUsername("alice").isEmpty());
    }

    @Test
    void cleanupExpiredEntries_shouldPurgeRegistry() throws Exception {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(1L, 10);
        PendingConfirmationManager manager = new PendingConfirmationManager(registry);
        manager.store(PendingConfirmationScope.CMD, "alice", "rm /tmp/old");

        Thread.sleep(5L);
        manager.cleanupExpiredEntries();

        assertTrue(registry.keysForUsername("alice").isEmpty());
    }

    @Test
    void keysForUsername_shouldReturnImmutableSnapshot() {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(120_000L, 2_000);
        registry.put("alice:cmd:rm /tmp/demo");

        var keys = registry.keysForUsername("alice");
        assertThrows(UnsupportedOperationException.class, keys::clear);
        assertTrue(registry.keysForUsername("alice").contains("alice:cmd:rm /tmp/demo"));
    }

    @Test
    void markShownIfPending_shouldBeAtomicAndOneShot() {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(120_000L, 2_000);
        String key = "alice:cmd:rm /tmp/demo";
        registry.put(key);

        assertTrue(registry.markShownIfPending(key));
        assertFalse(registry.markShownIfPending(key));
    }

    @Test
    void peek_shouldKeepTimestampAfterMarkShown() {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(120_000L, 2_000);
        String key = "alice:cmd:rm /tmp/demo";
        registry.put(key);

        Long initial = registry.peek(key);
        assertNotNull(initial);
        assertTrue(initial > 0);

        registry.markShown(key);
        Long shown = registry.peek(key);
        assertNotNull(shown);
        assertEquals(initial, shown);
    }

    @Test
    void entries_shouldReturnImmutableSnapshot() {
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(120_000L, 2_000);
        registry.put("alice:cmd:rm /tmp/demo");

        var snapshot = registry.entries();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(1, registry.entries().size());
    }

    @Test
    void clearScopesForUser_shouldOnlyClearSpecifiedScopes() {
        PendingConfirmationManager manager = new PendingConfirmationManager();
        manager.store(PendingConfirmationScope.CMD, "alice", "rm -rf /tmp/a");
        manager.store(PendingConfirmationScope.USER_ADD, "alice", "useradd -m -s /bin/bash devops");
        manager.store(PendingConfirmationScope.OFFLOAD, "alice", "offload-confirm --source /a --target /b");
        manager.store(PendingConfirmationScope.MOUNT, "alice", "mount-confirm --device /dev/sdc --target /mnt/data --fstype ext4 --options defaults");

        manager.clearScopesForUser("alice", PendingConfirmationScope.CMD, PendingConfirmationScope.USER_ADD);

        assertFalse(manager.has(PendingConfirmationScope.CMD, "alice", "rm -rf /tmp/a"));
        assertFalse(manager.has(PendingConfirmationScope.USER_ADD, "alice", "useradd -m -s /bin/bash devops"));
        assertTrue(manager.has(PendingConfirmationScope.OFFLOAD, "alice", "offload-confirm --source /a --target /b"));
        assertTrue(manager.has(PendingConfirmationScope.MOUNT, "alice", "mount-confirm --device /dev/sdc --target /mnt/data --fstype ext4 --options defaults"));
    }

    @Test
    void userAddScope_shouldUseSeparateCapacityBudget() {
        PendingConfirmationRegistry sharedRegistry = new PendingConfirmationRegistry(120_000L, 1);
        PendingConfirmationManager manager = new PendingConfirmationManager(sharedRegistry);
        String addCommand = "useradd -m -s /bin/bash devops";

        manager.storeWithPayload(PendingConfirmationScope.USER_ADD, "alice", addCommand, "secret");
        manager.store(PendingConfirmationScope.CMD, "alice", "cmd-1");
        manager.store(PendingConfirmationScope.CMD, "alice", "cmd-2");
        manager.store(PendingConfirmationScope.CMD, "alice", "cmd-3");

        assertTrue(manager.has(PendingConfirmationScope.CMD, "alice", "cmd-3"));
        assertFalse(manager.has(PendingConfirmationScope.CMD, "alice", "cmd-1"));
        assertEquals(
                "secret",
                manager.consumePayload(PendingConfirmationScope.USER_ADD, "alice", addCommand, String.class).orElse(null));
    }

    @Test
    void store_userAdd_shouldCompleteAfterCmdRegistryUnblocks() throws Exception {
        BlockingPutRegistry cmdRegistry = new BlockingPutRegistry(120_000L, 10);
        PendingConfirmationRegistry userAddRegistry = new PendingConfirmationRegistry(120_000L, 10);
        PendingConfirmationManager manager = new PendingConfirmationManager(cmdRegistry, userAddRegistry);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> blockedCmdStore = executor.submit(() ->
                    manager.store(PendingConfirmationScope.CMD, "alice", "rm -rf /tmp/blocking"));
            assertTrue(cmdRegistry.awaitPutStarted(1, TimeUnit.SECONDS));

            Future<?> userAddStore = executor.submit(() ->
                    manager.store(PendingConfirmationScope.USER_ADD, "alice", "useradd -m -s /bin/bash devops"));

            cmdRegistry.releasePut();
            userAddStore.get(1, TimeUnit.SECONDS);
            blockedCmdStore.get(1, TimeUnit.SECONDS);
        } finally {
            cmdRegistry.releasePut();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    @Test
    void clear_withSensitivePayload_shouldNotBlockSameScopeStore() throws Exception {
        PendingConfirmationManager manager = new PendingConfirmationManager();
        String command = "useradd -m -s /bin/bash security";
        BlockingSensitivePayload payload = new BlockingSensitivePayload();
        manager.storeWithPayload(PendingConfirmationScope.USER_ADD, "alice", command, payload);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> clearFuture = executor.submit(() ->
                    manager.clear(PendingConfirmationScope.USER_ADD, "alice", command));
            assertTrue(payload.awaitClearStarted(1, TimeUnit.SECONDS));

            Future<?> storeFuture = executor.submit(() ->
                    manager.store(PendingConfirmationScope.USER_ADD, "alice", "useradd -m -s /bin/bash devops2"));
            storeFuture.get(300, TimeUnit.MILLISECONDS);

            payload.releaseClear();
            clearFuture.get(1, TimeUnit.SECONDS);
        } finally {
            payload.releaseClear();
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingPutRegistry extends PendingConfirmationRegistry {
        private final CountDownLatch putStarted = new CountDownLatch(1);
        private final CountDownLatch releasePut = new CountDownLatch(1);

        private BlockingPutRegistry(long ttlMs, int maxSize) {
            super(ttlMs, maxSize);
        }

        @Override
        public void put(String key) {
            putStarted.countDown();
            awaitLatch(releasePut, 3, TimeUnit.SECONDS);
            super.put(key);
        }

        private boolean awaitPutStarted(long timeout, TimeUnit unit) {
            try {
                return putStarted.await(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        private void releasePut() {
            releasePut.countDown();
        }
    }

    private static final class BlockingSensitivePayload implements SensitivePendingPayload {
        private final CountDownLatch clearStarted = new CountDownLatch(1);
        private final CountDownLatch releaseClear = new CountDownLatch(1);

        @Override
        public void clearSensitiveData() {
            clearStarted.countDown();
            awaitLatch(releaseClear, 3, TimeUnit.SECONDS);
        }

        private boolean awaitClearStarted(long timeout, TimeUnit unit) {
            try {
                return clearStarted.await(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        private void releaseClear() {
            releaseClear.countDown();
        }
    }

    private static void awaitLatch(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            if (!latch.await(timeout, unit)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
