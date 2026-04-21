package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import io.github.luma.domain.model.VersionDiff;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialDeltaServicePerformanceTest {

    private final MaterialDeltaService service = new MaterialDeltaService();

    @Test
    void materialDeltaSummaryHandlesLargeDiffWithinBudget() {
        VersionDiff diff = new VersionDiff("left", "right", this.syntheticDiffEntries(40_000), 2_500);

        for (int warmup = 0; warmup < 3; warmup++) {
            this.service.summarize(diff);
        }

        long startedAt = System.nanoTime();
        List<io.github.luma.domain.model.MaterialDeltaEntry> result = List.of();
        for (int iteration = 0; iteration < 12; iteration++) {
            result = this.service.summarize(diff);
        }
        long elapsedNanos = System.nanoTime() - startedAt;

        assertFalse(result.isEmpty());
        assertWithin(Duration.ofMillis(1200), elapsedNanos, "Material delta summary regressed");
    }

    private List<DiffBlockEntry> syntheticDiffEntries(int count) {
        List<DiffBlockEntry> entries = new ArrayList<>(count);
        String[] leftStates = {
                "{Name:\"minecraft:stone\"}",
                "{Name:\"minecraft:air\"}",
                "{Name:\"minecraft:dirt\"}",
                "{Name:\"minecraft:oak_planks\"}"
        };
        String[] rightStates = {
                "{Name:\"minecraft:glass\"}",
                "{Name:\"minecraft:air\"}",
                "{Name:\"minecraft:grass_block\"}",
                "{Name:\"minecraft:oak_planks\"}"
        };

        for (int index = 0; index < count; index++) {
            String leftState = leftStates[index % leftStates.length];
            String rightState = rightStates[(index + 1) % rightStates.length];
            entries.add(new DiffBlockEntry(
                    new BlockPoint(index % 320, 60 + (index % 24), (index * 3) % 320),
                    leftState,
                    rightState,
                    switch (index % 3) {
                        case 0 -> ChangeType.ADDED;
                        case 1 -> ChangeType.REMOVED;
                        default -> ChangeType.CHANGED;
                    }
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
