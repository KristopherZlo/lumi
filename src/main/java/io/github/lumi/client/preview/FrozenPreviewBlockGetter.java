package io.github.lumi.client.preview;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.world.DecodedSection;
import java.util.Map;
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

/** Immutable commit block states with neutral light and live environmental tint. */
final class FrozenPreviewBlockGetter implements BlockAndTintGetter {
    private static final int PREVIEW_LIGHT = 15;
    private final BlockAndTintGetter lighting;
    private final Map<SectionKey, DecodedSection> sections;

    FrozenPreviewBlockGetter(
            BlockAndTintGetter lighting,
            Map<SectionKey, DecodedSection> sections) {
        this.lighting = Objects.requireNonNull(lighting, "lighting");
        this.sections = Map.copyOf(Objects.requireNonNull(sections, "sections"));
    }

    @Override public BlockState getBlockState(BlockPos position) {
        DecodedSection section = sections.get(sectionKey(position));
        return section == null
                ? Blocks.AIR.defaultBlockState()
                : section.blockStates().get(index(position));
    }

    @Override public FluidState getFluidState(BlockPos position) {
        return getBlockState(position).getFluidState();
    }

    @Override public BlockEntity getBlockEntity(BlockPos position) {
        return null;
    }

    @Override public float getShade(Direction direction, boolean shade) {
        return lighting.getShade(direction, shade);
    }

    @Override public LevelLightEngine getLightEngine() {
        return lighting.getLightEngine();
    }

    @Override public int getBlockTint(BlockPos position, ColorResolver resolver) {
        return lighting.getBlockTint(position, resolver);
    }

    @Override public int getBrightness(LightLayer layer, BlockPos position) {
        return PREVIEW_LIGHT;
    }

    @Override public int getRawBrightness(BlockPos position, int amount) {
        return PREVIEW_LIGHT;
    }

    @Override public boolean canSeeSky(BlockPos position) {
        return true;
    }

    @Override public int getHeight() {
        return lighting.getHeight();
    }

    @Override public int getMinY() {
        return lighting.getMinY();
    }

    static SectionKey sectionKey(BlockPos position) {
        return new SectionKey(
                Math.floorDiv(position.getX(), 16),
                Math.floorDiv(position.getY(), 16),
                Math.floorDiv(position.getZ(), 16));
    }

    static int index(BlockPos position) {
        int x = Math.floorMod(position.getX(), 16);
        int y = Math.floorMod(position.getY(), 16);
        int z = Math.floorMod(position.getZ(), 16);
        return x | z << 4 | y << 8;
    }
}
