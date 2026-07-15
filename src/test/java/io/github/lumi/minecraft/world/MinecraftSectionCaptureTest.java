package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class MinecraftSectionCaptureTest {
    @Test
    void mapsNegativeWorldCoordinatesToSectionAndLocalIndex() {
        BlockPos position = new BlockPos(-1, -1, -1);

        assertEquals(new SectionKey(-1, -1, -1), MinecraftSectionCapture.key(position));
        assertEquals(4095, MinecraftSectionCapture.localIndex(position));
        assertEquals(0, MinecraftSectionCapture.localIndex(new BlockPos(16, 32, 48)));
    }

    @Test
    void canonicalBlockEntityNbtKeepsTypeButRemovesImplicitWorldPosition() throws Exception {
        CompoundTag saved = new CompoundTag();
        saved.putString("id", "minecraft:chest");
        saved.putInt("x", 32);
        saved.putInt("y", 64);
        saved.putInt("z", -16);
        saved.putString("custom", "value");

        CompoundTag canonical = MinecraftNbtCodec.decode(
                MinecraftSectionCapture.canonicalBlockEntityNbt(saved));

        assertEquals("minecraft:chest", canonical.getStringOr("id", ""));
        assertEquals("value", canonical.getStringOr("custom", ""));
        assertFalse(canonical.contains("x"));
        assertFalse(canonical.contains("y"));
        assertFalse(canonical.contains("z"));
        assertTrue(saved.contains("x"));
    }
}
