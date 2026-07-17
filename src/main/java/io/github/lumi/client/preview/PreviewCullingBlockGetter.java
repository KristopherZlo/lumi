package io.github.lumi.client.preview;

import io.github.lumi.domain.model.BlockBox;
import java.util.Objects;
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

/** Exposes one preview box and replaces every outside neighbor with air. */
final class PreviewCullingBlockGetter implements BlockAndTintGetter {
    private final BlockAndTintGetter delegate;
    private final BlockBox bounds;

    PreviewCullingBlockGetter(BlockAndTintGetter delegate, BlockBox bounds) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
    }

    @Override public float getShade(Direction direction, boolean shade) {
        return delegate.getShade(direction, shade);
    }

    @Override public LevelLightEngine getLightEngine() {
        return delegate.getLightEngine();
    }

    @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return delegate.getBlockTint(pos, resolver);
    }

    @Override public int getBrightness(LightLayer layer, BlockPos pos) {
        return delegate.getBrightness(layer, pos);
    }

    @Override public int getRawBrightness(BlockPos pos, int amount) {
        return delegate.getRawBrightness(pos, amount);
    }

    @Override public boolean canSeeSky(BlockPos pos) {
        return delegate.canSeeSky(pos);
    }

    @Override public BlockEntity getBlockEntity(BlockPos pos) {
        return contains(pos) ? delegate.getBlockEntity(pos) : null;
    }

    @Override public BlockState getBlockState(BlockPos pos) {
        return contains(pos)
                ? delegate.getBlockState(pos) : Blocks.AIR.defaultBlockState();
    }

    @Override public FluidState getFluidState(BlockPos pos) {
        return contains(pos)
                ? delegate.getFluidState(pos)
                : Blocks.AIR.defaultBlockState().getFluidState();
    }

    @Override public int getHeight() {
        return delegate.getHeight();
    }

    @Override public int getMinY() {
        return delegate.getMinY();
    }

    private boolean contains(BlockPos pos) {
        return pos != null && bounds.contains(pos.getX(), pos.getY(), pos.getZ());
    }
}
