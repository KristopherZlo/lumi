package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.EntityPayload;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotCaptureServiceTest {

    @Test
    void derivesEntityCheckpointFromFullCaptureWithoutBlockPayloads() {
        CompoundTag entityTag = new CompoundTag();
        entityTag.putString("id", "minecraft:armor_stand");
        ChunkSnapshotPayload full = new ChunkSnapshotPayload(
                2,
                3,
                -64,
                320,
                List.of(new ChunkSectionSnapshotPayload(0, List.of(new CompoundTag()), new long[0], 0)),
                Map.of(1, new CompoundTag()),
                List.of(new EntityPayload(entityTag))
        );

        ChunkSnapshotPayload entityOnly = new SnapshotCaptureService().entityOnlyPayloads(List.of(full)).getFirst();

        assertTrue(entityOnly.sections().isEmpty());
        assertTrue(entityOnly.blockEntities().isEmpty());
        assertEquals(1, entityOnly.entitySnapshots().size());
        assertEquals("minecraft:armor_stand", entityOnly.entitySnapshots().getFirst().copyTag().getString("id").orElse(""));
    }
}
