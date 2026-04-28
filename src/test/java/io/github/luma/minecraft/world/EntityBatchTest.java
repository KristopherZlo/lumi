package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityBatchTest {

    @Test
    void copiesEntityTagsOnConstruction() {
        CompoundTag spawn = entity("minecraft:item", "00000000-0000-0000-0000-000000000001");
        EntityBatch batch = new EntityBatch(List.of(spawn), List.of(), List.of());

        spawn.putString("id", "minecraft:cow");

        assertEquals("minecraft:item", batch.entitiesToSpawn().getFirst().getString("id").orElse(""));
    }

    @Test
    void treatsUpdatesAsEntityWork() {
        EntityBatch batch = new EntityBatch(List.of(), List.of(), List.of(entity(
                "minecraft:cow",
                "00000000-0000-0000-0000-000000000002"
        )));

        assertFalse(batch.isEmpty());
    }

    @Test
    void preparedChunkBatchCarriesEntityBatchIntoChunkBatch() {
        EntityBatch entityBatch = new EntityBatch(
                List.of(entity("minecraft:item", "00000000-0000-0000-0000-000000000003")),
                List.of("00000000-0000-0000-0000-000000000004"),
                List.of()
        );
        PreparedChunkBatch prepared = new PreparedChunkBatch(new ChunkPoint(2, 3), List.of(), entityBatch);

        ChunkBatch chunkBatch = ChunkBatch.fromPrepared(prepared);

        assertTrue(chunkBatch.sections().isEmpty());
        assertEquals(entityBatch, chunkBatch.entityBatch());
    }

    @Test
    void entityOnlyPreparedOperationCountsEntityWorkUnits() {
        EntityBatch entityBatch = new EntityBatch(
                List.of(entity("minecraft:item", "00000000-0000-0000-0000-000000000005")),
                List.of("00000000-0000-0000-0000-000000000006"),
                List.of(entity("minecraft:cow", "00000000-0000-0000-0000-000000000007"))
        );
        PreparedChunkBatch prepared = new PreparedChunkBatch(new ChunkPoint(4, 5), List.of(), entityBatch);

        WorldOperationManager.PreparedApplyOperation operation =
                new WorldOperationManager.PreparedApplyOperation(List.of(prepared), () -> {
                });

        assertEquals(3, operation.totalWorkUnits());
    }

    @Test
    void chunkBatchCountsBlockEntityTailAndEntityWorkUnits() {
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");
        EntityBatch entityBatch = new EntityBatch(
                List.of(entity("minecraft:item", "00000000-0000-0000-0000-000000000008")),
                List.of(),
                List.of()
        );
        PreparedChunkBatch prepared = new PreparedChunkBatch(
                new ChunkPoint(6, 7),
                List.of(new PreparedBlockPlacement(new BlockPos(96, 64, 112), null, blockEntity)),
                entityBatch
        );

        ChunkBatch chunkBatch = ChunkBatch.fromPrepared(prepared);

        assertEquals(3, chunkBatch.totalWorkUnits());
    }

    private static CompoundTag entity(String type, String uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        return tag;
    }
}
