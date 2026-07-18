package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

class MinecraftEntityChunkCaptureTest {
    @Test
    void mapsNegativeChunkCoordinatesWithoutRepackingLoss() {
        assertEquals(new EntityChunkKey(-7, 11),
                MinecraftEntityChunkCapture.key(-7, 11));
    }

    @Test
    void canonicalEntityNbtLeavesIdentityOutsidePayloadAndNormalizesReloadFields()
            throws Exception {
        CompoundTag saved = new CompoundTag();
        saved.putString("id", "minecraft:armor_stand");
        saved.putIntArray("UUID", new int[] {0, 0, 0, 1});
        saved.putString("CustomName", "builder marker");
        ListTag rotation = new ListTag();
        rotation.add(FloatTag.valueOf(488.9948F));
        rotation.add(FloatTag.valueOf(15.0F));
        saved.put("Rotation", rotation);
        ListTag attributes = new ListTag();
        CompoundTag movement = new CompoundTag();
        movement.putString("id", "minecraft:movement_speed");
        CompoundTag attack = new CompoundTag();
        attack.putString("id", "minecraft:attack_damage");
        attributes.add(movement);
        attributes.add(attack);
        saved.put("attributes", attributes);

        CompoundTag canonical = MinecraftNbtCodec.decode(
                new MinecraftEntityChunkCapture().canonicalEntityNbt(saved));

        assertFalse(canonical.contains("id"));
        assertFalse(canonical.contains("UUID"));
        assertEquals("builder marker", canonical.getStringOr("CustomName", ""));
        assertEquals(488.9948F % 360.0F,
                canonical.getListOrEmpty("Rotation").getFloatOr(0, Float.NaN));
        assertEquals("minecraft:attack_damage", canonical.getListOrEmpty("attributes")
                .getCompoundOrEmpty(0).getStringOr("id", ""));
        assertTrue(saved.contains("id"));
        assertTrue(saved.contains("UUID"));
    }
}
