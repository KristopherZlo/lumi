package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class MinecraftEntityChunkCaptureTest {
    @Test
    void mapsNegativeChunkCoordinatesWithoutRepackingLoss() {
        assertEquals(new EntityChunkKey(-7, 11),
                MinecraftEntityChunkCapture.key(-7, 11));
    }

    @Test
    void canonicalEntityNbtLeavesIdentityOutsidePayload() throws Exception {
        CompoundTag saved = new CompoundTag();
        saved.putString("id", "minecraft:armor_stand");
        saved.putIntArray("UUID", new int[] {0, 0, 0, 1});
        saved.putString("CustomName", "builder marker");

        CompoundTag canonical = MinecraftNbtCodec.decode(
                MinecraftEntityChunkCapture.canonicalEntityNbt(saved));

        assertFalse(canonical.contains("id"));
        assertFalse(canonical.contains("UUID"));
        assertEquals("builder marker", canonical.getStringOr("CustomName", ""));
        assertTrue(saved.contains("id"));
        assertTrue(saved.contains("UUID"));
    }
}
