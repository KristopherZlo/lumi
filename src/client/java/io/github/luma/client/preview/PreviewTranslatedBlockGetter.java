package io.github.luma.client.preview;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

final class PreviewTranslatedBlockGetter implements BlockAndTintGetter {

    private final BlockAndTintGetter delegate;
    private final BlockPos origin;

    PreviewTranslatedBlockGetter(BlockAndTintGetter delegate, BlockPos origin) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.origin = Objects.requireNonNull(origin, "origin").immutable();
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        return this.delegate.getShade(direction, shade);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.delegate.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return this.delegate.getBlockTint(this.toWorld(pos), colorResolver);
    }

    @Override
    public int getBrightness(LightLayer lightLayer, BlockPos pos) {
        return this.delegate.getBrightness(lightLayer, this.toWorld(pos));
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return this.delegate.getRawBrightness(this.toWorld(pos), amount);
    }

    @Override
    public boolean canSeeSky(BlockPos pos) {
        return this.delegate.canSeeSky(this.toWorld(pos));
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return this.delegate.getBlockEntity(this.toWorld(pos));
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.delegate.getBlockState(this.toWorld(pos));
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return this.delegate.getFluidState(this.toWorld(pos));
    }

    @Override
    public int getHeight() {
        return this.delegate.getHeight();
    }

    @Override
    public int getMinY() {
        return this.delegate.getMinY() - this.origin.getY();
    }

    private BlockPos toWorld(BlockPos pos) {
        return new BlockPos(
                pos.getX() + this.origin.getX(),
                pos.getY() + this.origin.getY(),
                pos.getZ() + this.origin.getZ()
        );
    }
}
