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

class WorldApplyPerformanceGovernorTest {

    private final WorldApplyBudgetPlanner planner = new WorldApplyBudgetPlanner();

    @Test
    void maximumProfileStaysFastWhenObservedPressureIsLow() {
        WorldApplyPerformanceGovernor governor = new WorldApplyPerformanceGovernor();

        WorldApplyBudget initial = governor.planBudget(this.planner, 1.0D, WorldApplyProfile.MAXIMUM, 0.25D, 1.25D);
        governor.recordTick(5_000_000L, initial.maxNanos(), 0.25D, 1.25D);
        WorldApplyBudget recovered = governor.planBudget(this.planner, 1.0D, WorldApplyProfile.MAXIMUM, 0.25D, 1.25D);

        assertTrue(recovered.maxBlocks() >= initial.maxBlocks());
    }

    @Test
    void maximumProfileBacksOffWhenResponsiveTickPressureIsHigh() {
        WorldApplyPerformanceGovernor governor = new WorldApplyPerformanceGovernor();

        WorldApplyBudget initial = governor.planBudget(this.planner, 1.0D, WorldApplyProfile.MAXIMUM, 0.25D, 1.25D);
        governor.recordTick(100_000_000L, initial.maxNanos(), 0.25D, 1.25D);
        WorldApplyBudget reduced = governor.planBudget(this.planner, 1.0D, WorldApplyProfile.MAXIMUM, 0.25D, 1.25D);

        assertTrue(reduced.maxBlocks() < initial.maxBlocks());
        assertTrue(reduced.maxNanos() < initial.maxNanos());
    }

    @Test
    void defersNextChunkWhenObservedCostPredictsResponsiveWindowOverrun() {
        WorldApplyPerformanceGovernor governor = new WorldApplyPerformanceGovernor();
        WorldApplyBudget budget = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.MAXIMUM);
        ChunkBatch chunk = this.sparseChunk(100);
        governor.recordWork(ApplyWorkKind.SPARSE_DIRECT, 100, 20_000_000L);

        assertTrue(governor.evaluateChunkStart(chunk, budget, 5_000_000L, 100).allowed());
        assertFalse(governor.evaluateChunkStart(chunk, budget, 35_000_000L, 100).allowed());
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
