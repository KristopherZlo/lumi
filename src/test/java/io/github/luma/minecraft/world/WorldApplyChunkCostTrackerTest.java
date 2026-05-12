package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyChunkCostTrackerTest {

    private final WorldApplyBudget maximumBudget = new WorldApplyBudget(
            1_000_000,
            200_000_000L,
            1024,
            1_000_000,
            1024,
            2048,
            131_072,
            65_536,
            524_288,
            512,
            512,
            2048,
            1024
    );

    @Test
    void doesNotDeferFirstChunkBeforeAnyWorkWasProcessed() {
        WorldApplyChunkCostTracker tracker = new WorldApplyChunkCostTracker();

        assertFalse(tracker.shouldDeferChunk(this.sparseChunk(32), this.maximumBudget, 60_000_000L, 0));
    }

    @Test
    void defersUnknownNextChunkAfterResponsiveTickBudgetIsConsumed() {
        WorldApplyChunkCostTracker tracker = new WorldApplyChunkCostTracker();

        assertTrue(tracker.shouldDeferChunk(this.sparseChunk(32), this.maximumBudget, 50_000_000L, 128));
    }

    @Test
    void usesObservedChunkCostToAvoidStartingChunkThatWouldExceedRemainingTickWindow() {
        WorldApplyChunkCostTracker tracker = new WorldApplyChunkCostTracker();
        ChunkBatch chunk = this.sparseChunk(128);
        tracker.recordChunk(chunk, 20_000_000L);

        assertFalse(tracker.shouldDeferChunk(chunk, this.maximumBudget, 5_000_000L, 128));
        assertTrue(tracker.shouldDeferChunk(chunk, this.maximumBudget, 35_000_000L, 128));
    }

    private ChunkBatch sparseChunk(int placements) {
        List<PreparedBlockPlacement> preparedPlacements = new ArrayList<>();
        BitSet changedCells = new BitSet(4096);
        for (int index = 0; index < placements; index++) {
            BlockPos pos = new BlockPos(index & 15, index >> 8, (index >> 4) & 15);
            preparedPlacements.add(new PreparedBlockPlacement(pos, null, null));
            changedCells.set(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
        }
        return new ChunkBatch(
                new ChunkPoint(0, 0),
                Map.of(0, new SectionBatch(0, changedCells, preparedPlacements)),
                Map.of(),
                EntityBatch.empty(),
                BatchState.COMPLETE
        );
    }
}
