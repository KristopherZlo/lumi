package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.SectionBlob;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MinecraftSectionCaptureTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void mapsNegativeWorldCoordinatesToSectionAndLocalIndex() {
        BlockPos position = new BlockPos(-1, -1, -1);

        assertEquals(new SectionKey(-1, -1, -1), MinecraftSectionCapture.key(position));
        assertEquals(4095, MinecraftSectionCapture.localIndex(position));
        assertEquals(0, MinecraftSectionCapture.localIndex(new BlockPos(16, 32, 48)));
        assertEquals(new BlockPos(-1, -1, -1),
                MinecraftPreparedWorldAccess.position(new SectionKey(-1, -1, -1), 4095));
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

    @Test
    void comparesNativeSectionStatesWithoutSerializingThem() {
        var section = new LevelChunkSection(new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)), null);
        section.setBlockState(1, 2, 3, Blocks.STONE.defaultBlockState(), false);
        var expected = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, Blocks.AIR.defaultBlockState()));
        expected.set((2 << 8) | (3 << 4) | 1, Blocks.STONE.defaultBlockState());

        assertTrue(MinecraftSectionCapture.matchesStates(section, expected));
        expected.set(0, Blocks.DIRT.defaultBlockState());
        assertFalse(MinecraftSectionCapture.matchesStates(section, expected));
    }
}
