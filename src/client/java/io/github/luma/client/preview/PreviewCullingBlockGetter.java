package io.github.luma.client.preview;

import io.github.luma.domain.model.Bounds3i;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.client.multiplayer.ClientLevel;

final class PreviewCullingBlockGetter implements BlockAndTintGetter {

    private final ClientLevel level;
    private final Bounds3i bounds;

    PreviewCullingBlockGetter(ClientLevel level, Bounds3i bounds) {
        this.level = level;
        this.bounds = bounds;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return this.level.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.level.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return this.level.getBlockTint(pos, colorResolver);
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        return this.level.getBrightness(lightLayer, pos);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return this.level.getRawBrightness(pos, amount);
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return this.level.canSeeSky(pos);
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.contains(pos) ? this.level.getBlockEntity(pos) : null;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.contains(pos) ? this.level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.contains(pos) ? this.level.getFluidState(pos) : Blocks.AIR.defaultBlockState().getFluidState();
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public int getMinY() {
        return this.level.getMinY();
    }

    private boolean contains(BlockPos pos) {
        return pos != null
                && pos.getX() >= this.bounds.min().x()
                && pos.getX() <= this.bounds.max().x()
                && pos.getY() >= this.bounds.min().y()
                && pos.getY() <= this.bounds.max().y()
                && pos.getZ() >= this.bounds.min().z()
                && pos.getZ() <= this.bounds.max().z();
    }
}
