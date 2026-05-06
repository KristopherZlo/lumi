package io.github.luma.minecraft.capture;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySnapshotServiceTest {

    @Test
    void normalizationRemovesTickVolatileEntityTags() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:item");
        tag.putString("UUID", "00000000-0000-0000-0000-000000000001");
        tag.putInt("Age", 12);
        tag.putString("Motion", "transient");
        tag.putInt("PickupDelay", 20);

        CompoundTag normalized = EntitySnapshotService.normalizeForHistory(tag);

        assertFalse(normalized.contains("Age"));
        assertFalse(normalized.contains("Motion"));
        assertFalse(normalized.contains("PickupDelay"));
        assertTrue(normalized.contains("id"));
        assertTrue(normalized.contains("UUID"));
    }
}
