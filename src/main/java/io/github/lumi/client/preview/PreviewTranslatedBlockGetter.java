package io.github.lumi.client.preview;

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

/** Maps preview-local renderer coordinates back into the captured world box. */
final class PreviewTranslatedBlockGetter implements BlockAndTintGetter {
    private final BlockAndTintGetter delegate;
    private final BlockPos origin;

    PreviewTranslatedBlockGetter(BlockAndTintGetter delegate, BlockPos origin) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.origin = Objects.requireNonNull(origin, "origin").immutable();
    }

    @Override public float getShade(Direction direction, boolean shade) {
        return delegate.getShade(direction, shade);
    }

    @Override public LevelLightEngine getLightEngine() {
        return delegate.getLightEngine();
    }

    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return delegate.getBlockTint(toWorld(pos), resolver);
    }

    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        return delegate.getBrightness(layer, toWorld(pos));
    }

    @Override public int getRawBrightness(BlockPos pos, int amount) {
        return delegate.getRawBrightness(toWorld(pos), amount);
    }

    @Override public boolean canSeeSky(BlockPos pos) {
        return delegate.canSeeSky(toWorld(pos));
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        return delegate.getBlockEntity(toWorld(pos));
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        return delegate.getBlockState(toWorld(pos));
    }

    @Override public FluidState getFluidState(BlockPos pos) {
        return delegate.getFluidState(toWorld(pos));
    }

    @Override public int getHeight() {
        return delegate.getHeight();
    }

    @Override public int getMinY() {
        return delegate.getMinY() - origin.getY();
    }

    private BlockPos toWorld(BlockPos pos) {
        return new BlockPos(
                pos.getX() + origin.getX(),
                pos.getY() + origin.getY(),
                pos.getZ() + origin.getZ());
    }
}
