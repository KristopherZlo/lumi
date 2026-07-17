package io.github.luma.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class WorldMutationBarrierLifecycleTest {

    private static final Path MANAGER =
            Path.of("src/main/java/io/github/luma/minecraft/world/WorldOperationManager.java");
    private static final Path BARRIER =
            Path.of("src/main/java/io/github/luma/minecraft/world/WorldMutationBarrier.java");

    @Test
    void mutationLockIsReleasedBeforeFailureBookkeeping() throws Exception {
        String source = Files.readString(MANAGER);
        int complete = source.indexOf("private synchronized void complete");
        int release = source.indexOf("this.mutationBarrier.release(operation);", complete);
        int bookkeeping = source.indexOf("this.recordReliabilityFailure(server, operation);", complete);

        assertTrue(release > complete);
        assertTrue(release < bookkeeping);
    }

    @Test
    void staleAndShutdownLocksAreRecovered() throws Exception {
        String manager = Files.readString(MANAGER);
        String barrier = Files.readString(BARRIER);

        assertTrue(manager.contains("this.mutationBarrier.reconcile(level.getServer(), active);"));
        assertTrue(manager.contains("this.mutationBarrier.releaseAll(server);"));
        assertTrue(barrier.contains("entry.getKey() != activeOperation"));
        assertFalse(barrier.contains("blockedServers"));
    }

    @Test
    void lockLifecycleAndFirstRejectedPlayerMutationAreVisibleInLatestLog() throws Exception {
        String barrier = Files.readString(BARRIER);

        assertTrue(barrier.contains("World mutations locked for operation"));
        assertTrue(barrier.contains("World mutations unlocked for operation"));
        assertTrue(barrier.contains("Rejected player world mutation"));
        assertTrue(barrier.contains("Recovered stale world mutation lock"));
    }
}
