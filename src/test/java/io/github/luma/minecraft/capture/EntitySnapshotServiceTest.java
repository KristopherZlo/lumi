package io.github.luma.minecraft.capture;

import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntitySnapshotServiceTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void normalizationPreservesEntityNbtForReplay() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:item");
        tag.putString("UUID", "00000000-0000-0000-0000-000000000001");
        tag.putInt("Age", 12);
        tag.putString("Motion", "transient");
        tag.putInt("PickupDelay", 20);
        tag.putString("variant", "minecraft:cold");

        CompoundTag normalized = EntitySnapshotService.normalizeForHistory(tag);

        assertEquals(12, normalized.getIntOr("Age", -1));
        assertEquals("transient", normalized.getString("Motion").orElse(""));
        assertEquals(20, normalized.getIntOr("PickupDelay", -1));
        assertEquals("minecraft:cold", normalized.getString("variant").orElse(""));
        assertTrue(normalized.contains("id"));
        assertTrue(normalized.contains("UUID"));
    }

    @Test
    void sanitizerDefaultsNullArrowPickupBeforeVanillaSave() {
        Arrow arrow = new Arrow(EntityType.ARROW, null);
        arrow.pickup = null;

        boolean sanitized = new EntitySnapshotSanitizer().sanitize(arrow);

        assertTrue(sanitized);
        assertEquals(AbstractArrow.Pickup.DISALLOWED, arrow.pickup);
    }
}
