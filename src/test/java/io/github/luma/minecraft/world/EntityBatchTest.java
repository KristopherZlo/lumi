package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.List;
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

    private static CompoundTag entity(String type, String uuid) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        return tag;
    }
}
