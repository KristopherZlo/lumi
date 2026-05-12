package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyCostModelTest {

    @Test
    void recordsCostsByIndependentWorkKind() {
        ApplyCostModel model = new ApplyCostModel();

        model.record(ApplyWorkKind.SPARSE_DIRECT, 100, 10_000_000L);
        model.record(ApplyWorkKind.SECTION_REWRITE, 1, 30_000_000L);

        assertTrue(model.estimateNanos(ApplyWorkKind.SPARSE_DIRECT, 100) < model.estimateNanos(ApplyWorkKind.SECTION_REWRITE, 1));
        assertEquals(0L, model.estimateNanos(ApplyWorkKind.BLOCK_ENTITY, 4));
    }

    @Test
    void estimatesChunkFromObservedShapeSpecificCosts() {
        ApplyCostModel model = new ApplyCostModel();
        model.record(ApplyWorkKind.SPARSE_DIRECT, 100, 10_000_000L);
        model.record(ApplyWorkKind.BLOCK_ENTITY, 2, 4_000_000L);

        long estimated = model.estimateChunkNanos(this.sparseChunk(100, 2));

        assertTrue(estimated >= 14_000_000L);
    }

    private ChunkBatch sparseChunk(int placements, int blockEntities) {
        List<PreparedBlockPlacement> preparedPlacements = new ArrayList<>();
        BitSet changedCells = new BitSet(4096);
        for (int index = 0; index < placements; index++) {
            BlockPos pos = new BlockPos(index & 15, index >> 8, (index >> 4) & 15);
            preparedPlacements.add(new PreparedBlockPlacement(pos, null, null));
            changedCells.set(((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15));
        }
        Map<BlockPos, net.minecraft.nbt.CompoundTag> blockEntityMap = blockEntities <= 0
                ? Map.of()
                : Map.of(new BlockPos(0, 0, 0), new net.minecraft.nbt.CompoundTag(), new BlockPos(1, 0, 0), new net.minecraft.nbt.CompoundTag());
        return new ChunkBatch(
                new ChunkPoint(0, 0),
                Map.of(0, new SectionBatch(0, changedCells, preparedPlacements)),
                blockEntityMap,
                EntityBatch.empty(),
                BatchState.COMPLETE
        );
    }
}
