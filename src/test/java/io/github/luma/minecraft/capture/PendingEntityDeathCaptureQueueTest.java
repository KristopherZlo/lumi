package io.github.luma.minecraft.capture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingEntityDeathCaptureQueueTest {

    @Test
    void deathQueueDrainsBatchesWithoutUndoOnlyFlag() throws Exception {
        String queue = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/capture/PendingEntityDeathCaptureQueue.java")
        );
        String tracker = Files.readString(
                Path.of("src/main/java/io/github/luma/minecraft/capture/EntityMutationTracker.java")
        );

        assertFalse(queue.contains("boolean undoOnly"));
        assertTrue(queue.contains("List.copyOf(worldCaptures.values())"));
        assertTrue(tracker.contains("DEATH_CAPTURE_QUEUE.drain(server, EntityMutationTracker::recordDeaths)"));
    }
}
