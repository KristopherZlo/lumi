package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MinecraftSectionRewriterTest {
    @Test
    void packsChangedLightCellsBySectionColumn() {
        short[] updates = new short[256];

        MinecraftSectionRewriter.markLightChange(updates, 3, 7, 5);
        MinecraftSectionRewriter.markLightChange(updates, 12, 7, 5);

        assertEquals((1 << 3) | (1 << 12), updates[(5 << 4) | 7]);
    }
}
