package io.github.luma.domain.service;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RestoreServiceTest {

    @Test
    void collapsePreparedBatchesKeepsOnlyLastPlacementPerBlock() {
        PreparedChunkBatch first = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(1, 64, 1), null, null),
                        new PreparedBlockPlacement(new BlockPos(2, 64, 2), null, null)
                )
        );
        PreparedChunkBatch second = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(1, 64, 1), null, null)
                )
        );

        List<PreparedChunkBatch> collapsed = RestoreService.collapsePreparedBatches(List.of(first, second));

        assertEquals(1, collapsed.size());
        assertEquals(2, collapsed.getFirst().placements().size());
        assertEquals(new BlockPos(1, 64, 1), collapsed.getFirst().placements().getFirst().pos());
    }

    @Test
    void collapsePreparedBatchesKeepsEntityOnlyBatches() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:block_display");
        entity.putString("UUID", "00000000-0000-0000-0000-000000000050");
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(2, 3),
                List.of(),
                new EntityBatch(List.of(entity), List.of(), List.of())
        );

        List<PreparedChunkBatch> collapsed = RestoreService.collapsePreparedBatches(List.of(batch));

        assertEquals(1, collapsed.size());
        assertEquals(1, collapsed.getFirst().entityBatch().entitiesToSpawn().size());
    }
}
