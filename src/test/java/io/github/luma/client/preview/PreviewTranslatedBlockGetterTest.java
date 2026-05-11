package io.github.luma.client.preview;

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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewTranslatedBlockGetterTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void translatesLocalPreviewPositionsToWorldLookups() {
        FakeBlockAndTintGetter delegate = new FakeBlockAndTintGetter();
        BlockPos origin = new BlockPos(20, 70, -8);
        BlockPos local = new BlockPos(2, 3, 4);
        BlockPos world = new BlockPos(22, 73, -4);
        BlockState water = Blocks.WATER.defaultBlockState();
        delegate.set(world, water);

        PreviewTranslatedBlockGetter translated = new PreviewTranslatedBlockGetter(delegate, origin);

        assertEquals(water, translated.getBlockState(local));
        assertEquals(world, delegate.lastBlockStatePos());
        assertEquals(water.getFluidState().getType(), translated.getFluidState(local).getType());
        assertEquals(world, delegate.lastFluidStatePos());
        assertEquals(-134, translated.getMinY());
    }

    private static final class FakeBlockAndTintGetter implements BlockAndTintGetter {

        private final Map<String, BlockState> states = new HashMap<>();
        private BlockPos lastBlockStatePos;
        private BlockPos lastFluidStatePos;

        private void set(BlockPos pos, BlockState state) {
            this.states.put(key(pos), state);
        }

        private BlockPos lastBlockStatePos() {
            return this.lastBlockStatePos;
        }

        private BlockPos lastFluidStatePos() {
            return this.lastFluidStatePos;
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return 1.0F;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return null;
        }

        @Override
        public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
            return 0xFFFFFF;
        }

        @Override
        public int getBrightness(LightLayer lightLayer, BlockPos pos) {
            return 15;
        }

        @Override
        public int getRawBrightness(BlockPos pos, int amount) {
            return 15;
        }

        @Override
        public boolean canSeeSky(BlockPos pos) {
            return false;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            this.lastBlockStatePos = pos.immutable();
            return this.states.getOrDefault(key(pos), Blocks.AIR.defaultBlockState());
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            this.lastFluidStatePos = pos.immutable();
            return this.states.getOrDefault(key(pos), Blocks.AIR.defaultBlockState()).getFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinY() {
            return -64;
        }

        private static String key(BlockPos pos) {
            return pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        }
    }
}
