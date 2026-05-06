package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import java.util.List;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EntitySnapshotOverrideTest {

    @Test
    void spawnBaselineExcludesNewEntity() {
        EntityPayload spawned = entity("minecraft:item", "00000000-0000-0000-0000-000000000010");
        EntitySnapshotOverride override = new EntitySnapshotOverride(null, spawned);

        assertEquals(List.of(), override.applyTo(List.of(spawned)));
    }

    @Test
    void removeBaselineRestoresOldEntity() {
        EntityPayload oldPayload = entity("minecraft:item", "00000000-0000-0000-0000-000000000011");
        EntitySnapshotOverride override = new EntitySnapshotOverride(oldPayload, null);

        assertEquals(List.of(oldPayload), override.applyTo(List.of()));
    }

    @Test
    void updateBaselineReplacesLiveEntityWithOldPayload() {
        String entityId = "00000000-0000-0000-0000-000000000012";
        EntityPayload oldPayload = entity("minecraft:item", entityId, "old");
        EntityPayload newPayload = entity("minecraft:item", entityId, "new");
        EntityPayload otherPayload = entity("minecraft:item", "00000000-0000-0000-0000-000000000013");
        EntitySnapshotOverride override = new EntitySnapshotOverride(oldPayload, newPayload);

        assertEquals(List.of(otherPayload, oldPayload), override.applyTo(List.of(newPayload, otherPayload)));
    }

    @Test
    void movedEntityBaselineOnlyAddsOldPayloadToOldChunk() {
        String entityId = "00000000-0000-0000-0000-000000000014";
        EntityPayload oldPayload = entityAt("minecraft:cow", entityId, 1.0D);
        EntityPayload newPayload = entityAt("minecraft:cow", entityId, 32.0D);
        EntitySnapshotOverride override = new EntitySnapshotOverride(oldPayload, newPayload);

        assertEquals(List.of(oldPayload), override.applyTo(List.of(), new ChunkPoint(0, 0)));
        assertEquals(List.of(), override.applyTo(List.of(newPayload), new ChunkPoint(2, 0)));
    }

    private static EntityPayload entity(String type, String uuid) {
        return entity(type, uuid, "");
    }

    private static EntityPayload entity(String type, String uuid, String customName) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("id", type);
        tag.putString("UUID", uuid);
        if (!customName.isBlank()) {
            tag.putString("CustomName", customName);
        }
        return new EntityPayload(tag);
    }

    private static EntityPayload entityAt(String type, String uuid, double x) {
        net.minecraft.nbt.CompoundTag tag = entity(type, uuid).copyTag();
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
