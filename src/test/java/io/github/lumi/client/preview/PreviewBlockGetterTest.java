package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BlockBox;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreviewBlockGetterTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void cullsOutsideBlocksAndRetainsInsideState() {
        FakeBlocks source = new FakeBlocks();
        source.set(new BlockPos(4, 5, 6), Blocks.STONE.defaultBlockState());
        source.set(new BlockPos(5, 5, 6), Blocks.WATER.defaultBlockState());
        var culled = new PreviewCullingBlockGetter(
                source, new BlockBox(4, 5, 6, 4, 5, 6));

        assertEquals(Blocks.STONE.defaultBlockState(),
                culled.getBlockState(new BlockPos(4, 5, 6)));
        assertEquals(Blocks.AIR.defaultBlockState(),
                culled.getBlockState(new BlockPos(5, 5, 6)));
        assertEquals(Fluids.EMPTY.defaultFluidState(),
                culled.getFluidState(new BlockPos(5, 5, 6)));
    }

    @Test
    void translatesLocalPreviewPositionsToWorldLookups() {
        FakeBlocks source = new FakeBlocks();
        BlockPos world = new BlockPos(22, 73, -4);
        source.set(world, Blocks.WATER.defaultBlockState());
        var translated = new PreviewTranslatedBlockGetter(
                source, new BlockPos(20, 70, -8));

        assertEquals(Blocks.WATER.defaultBlockState(),
                translated.getBlockState(new BlockPos(2, 3, 4)));
        assertEquals(world, source.lastLookup);
        assertEquals(-134, translated.getMinY());
    }

    private static final class FakeBlocks implements BlockAndTintGetter {
        private final Map<BlockPos, BlockState> states = new HashMap<>();
        private BlockPos lastLookup;

        private void set(BlockPos pos, BlockState state) {
            states.put(pos.immutable(), state);
        }

        @Override public float getShade(Direction direction, boolean shade) { return 1; }
        @Override public LevelLightEngine getLightEngine() { return null; }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return 0xffffff; }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) { return 15; }
        @Override public int getRawBrightness(BlockPos pos, int amount) { return 15; }
        @Override public boolean canSeeSky(BlockPos pos) { return false; }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            lastLookup = pos.immutable();
            return states.getOrDefault(lastLookup, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinY() { return -64; }
    }
}
