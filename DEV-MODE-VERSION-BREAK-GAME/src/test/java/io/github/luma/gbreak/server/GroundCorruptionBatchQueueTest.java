package io.github.luma.gbreak.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class GroundCorruptionBatchQueueTest {

    @Test
    void loadMarksPlanAndDrainsInOrder() {
        GroundCorruptionBatchQueue<String> queue = new GroundCorruptionBatchQueue<>();

        queue.load(List.of("first", "second"));

        assertTrue(queue.isPlanned());
        assertTrue(queue.hasPending());
        assertEquals("first", queue.removeFirst());
        assertEquals("second", queue.removeFirst());
        assertFalse(queue.hasPending());
        assertTrue(queue.isPlanned());
    }

    @Test
    void resetClearsPendingCandidatesAndPlanState() {
        GroundCorruptionBatchQueue<String> queue = new GroundCorruptionBatchQueue<>();
        queue.load(List.of("stale"));

        queue.reset();

        assertFalse(queue.isPlanned());
        assertFalse(queue.hasPending());
    }
}
