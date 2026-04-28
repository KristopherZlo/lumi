package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareOverlayRendererPerformanceTest {

    private static final int ENTRY_COUNT = 50_000;
    private static final int ITERATIONS = 10;

    @Test
    void nearestEntrySelectionStaysWithinBudget() {
        List<DiffBlockEntry> changedBlocks = this.syntheticEntries(ENTRY_COUNT);

        for (int warmup = 0; warmup < 3; warmup++) {
            CompareOverlayRenderer.selectNearestEntries(changedBlocks, warmup * 4.0D, 96.0D, warmup * 6.0D);
        }

        long startedAt = System.nanoTime();
        List<DiffBlockEntry> lastSelection = List.of();
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            lastSelection = CompareOverlayRenderer.selectNearestEntries(
                    changedBlocks,
                    32.0D + (iteration * 3.5D),
                    96.0D,
                    -48.0D + (iteration * 2.5D)
            );
        }
        long elapsedNanos = System.nanoTime() - startedAt;

        assertEquals(2048, lastSelection.size());
        assertWithin(Duration.ofMillis(1500), elapsedNanos, "Compare overlay nearest-entry selection regressed");
    }

    @Test
    void nearestEntrySelectionHandlesVeryLargeDiffs() {
        List<DiffBlockEntry> changedBlocks = this.syntheticEntries(500_000);

        long startedAt = System.nanoTime();
        List<DiffBlockEntry> selection = CompareOverlayRenderer.selectNearestEntries(
                changedBlocks,
                128.0D,
                96.0D,
                128.0D
        );
        long elapsedNanos = System.nanoTime() - startedAt;

        assertEquals(2048, selection.size());
        assertWithin(Duration.ofMillis(2500), elapsedNanos, "Compare overlay very-large selection regressed");
    }

    private List<DiffBlockEntry> syntheticEntries(int count) {
        List<DiffBlockEntry> entries = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            ChangeType type = switch (index % 3) {
                case 0 -> ChangeType.ADDED;
                case 1 -> ChangeType.REMOVED;
                default -> ChangeType.CHANGED;
            };
            entries.add(new DiffBlockEntry(
                    new BlockPoint(index % 512, 64 + (index % 48), (index * 7) % 512),
                    "minecraft:stone",
                    "minecraft:glass",
                    type
            ));
        }
        return List.copyOf(entries);
    }

    private static void assertWithin(Duration budget, long elapsedNanos, String message) {
        assertTrue(
                elapsedNanos <= budget.toNanos(),
                message + ": expected <= " + budget.toMillis() + " ms but was " + Duration.ofNanos(elapsedNanos).toMillis() + " ms"
        );
    }
}
