package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MinecraftSectionRewriterTest {
    @Test
    void packsChangedLightCellsBySectionColumn() {
        short[] updates = new short[256];

        MinecraftSectionRewriter.markLightChange(updates, 3, 7, 5);
        MinecraftSectionRewriter.markLightChange(updates, 12, 7, 5);

        assertEquals((1 << 3) | (1 << 12), updates[(5 << 4) | 7]);
    }

    @Test
    void selectsOneFullPacketForDenseOrBlockEntityChanges() {
        assertFalse(MinecraftSectionRewriter.useFullChunkPacket(1023, false));
        assertTrue(MinecraftSectionRewriter.useFullChunkPacket(1024, false));
        assertTrue(MinecraftSectionRewriter.useFullChunkPacket(1, true));
    }

    @Test
    void selectsNativeFullRelightOnlyForDenseLightChanges() {
        assertFalse(MinecraftSectionRewriter.useFullRelight(1023));
        assertTrue(MinecraftSectionRewriter.useFullRelight(1024));
    }
}
