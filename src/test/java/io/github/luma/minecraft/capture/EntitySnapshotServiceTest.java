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
    void normalizationClearsDeathAndIgnitionStateButKeepsMotion() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:creeper");
        tag.putString("UUID", "00000000-0000-0000-0000-000000000002");
        tag.putShort("DeathTime", (short) 18);
        tag.putShort("HurtTime", (short) 9);
        tag.putShort("Fire", (short) 120);
        tag.putFloat("Health", 0.0F);
        tag.putBoolean("ignited", true);
        tag.putBoolean("powered", true);
        tag.putString("Motion", "keep");
        tag.putShort("Fuse", (short) 4);
        tag.putShort("ExplosionRadius", (short) 3);

        CompoundTag normalized = EntitySnapshotService.normalizeForHistory(tag);

        assertEquals(0, normalized.getShortOr("DeathTime", (short) 0));
        assertEquals(0, normalized.getShortOr("HurtTime", (short) 0));
        assertEquals(0, normalized.getShortOr("Fire", (short) 0));
        assertEquals(1.0F, normalized.getFloatOr("Health", 0.0F));
        assertEquals(false, normalized.getBooleanOr("ignited", false));
        assertTrue(normalized.getBooleanOr("powered", false));
        assertEquals("keep", normalized.getString("Motion").orElse(""));
        assertEquals(30, normalized.getShortOr("Fuse", (short) 0));
        assertTrue(normalized.contains("ExplosionRadius"));
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
