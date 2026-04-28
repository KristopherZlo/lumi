package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.EntityPayload;
import java.util.List;
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
}
