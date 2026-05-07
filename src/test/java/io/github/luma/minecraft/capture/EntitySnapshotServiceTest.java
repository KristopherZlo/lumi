package io.github.luma.minecraft.capture;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySnapshotServiceTest {

    @Test
    void normalizationPreservesEntityNbtForReplay() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:item");
        tag.putString("UUID", "00000000-0000-0000-0000-000000000001");
        tag.putInt("Age", 12);
        tag.putString("Motion", "transient");
        tag.putInt("PickupDelay", 20);

        CompoundTag normalized = EntitySnapshotService.normalizeForHistory(tag);

        assertEquals(12, normalized.getIntOr("Age", -1));
        assertEquals("transient", normalized.getString("Motion").orElse(""));
        assertEquals(20, normalized.getIntOr("PickupDelay", -1));
        assertTrue(normalized.contains("id"));
        assertTrue(normalized.contains("UUID"));
    }
}
