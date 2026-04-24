package io.github.luma.gbreak.server;

import io.github.luma.gbreak.corruption.CorruptionMaskSampler;
import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;

final class SkyDisplayPlacementPlanner {

    private static final int HORIZONTAL_RADIUS = 52;
    private static final int MIN_HEIGHT_OFFSET = 18;
    private static final int MAX_HEIGHT_OFFSET = 46;
    private static final int DISPLAY_COLUMN_SPACING = 4;
    private static final long HEIGHT_HASH_SALT = 0x47425245414B534BL;

    private final CorruptionSettings settings;
    private final CorruptionMaskSampler maskSampler;

    SkyDisplayPlacementPlanner(CorruptionSettings settings, CorruptionMaskSampler maskSampler) {
        this.settings = settings;
        this.maskSampler = maskSampler;
    }

    List<BlockPos> plan(BlockPos origin, int worldBottomY, int worldTopYInclusive, int maxDisplays) {
        if (maxDisplays <= 0) {
            return List.of();
        }

        HeightBand heightBand = this.resolveHeightBand(origin, worldBottomY, worldTopYInclusive);
        if (heightBand == null) {
            return List.of();
        }

        List<BlockPos> positions = new ArrayList<>(maxDisplays);
        for (BlockPos offset : this.maskSampler.buildSurfaceOffsets(HORIZONTAL_RADIUS)) {
            if (positions.size() >= maxDisplays) {
                return List.copyOf(positions);
            }

            int x = origin.getX() + offset.getX();
            int z = origin.getZ() + offset.getZ();
            if (!this.isDisplayColumn(x, z)) {
                continue;
            }

            double noiseValue = this.maskSampler.noiseValue(x, z, this.settings);
            if (!this.maskSampler.isWorldMaskColumn(x, z, noiseValue, this.settings)) {
                continue;
            }

            positions.add(new BlockPos(x, this.stableY(x, z, heightBand), z));
        }

        return List.copyOf(positions);
    }

    private HeightBand resolveHeightBand(BlockPos origin, int worldBottomY, int worldTopYInclusive) {
        int safeBottomY = worldBottomY + 4;
        int safeTopY = worldTopYInclusive - 4;
        if (safeBottomY > safeTopY) {
            return null;
        }

        int minY = Math.max(safeBottomY, origin.getY() + MIN_HEIGHT_OFFSET);
        int maxY = Math.min(safeTopY, origin.getY() + MAX_HEIGHT_OFFSET);
        if (minY <= maxY) {
            return new HeightBand(minY, maxY);
        }

        int clampedY = this.clamp(origin.getY() + MIN_HEIGHT_OFFSET, safeBottomY, safeTopY);
        return new HeightBand(clampedY, clampedY);
    }

    private boolean isDisplayColumn(int x, int z) {
        return Math.floorMod(x, DISPLAY_COLUMN_SPACING) == 0
                && Math.floorMod(z, DISPLAY_COLUMN_SPACING) == 0;
    }

    private int stableY(int x, int z, HeightBand heightBand) {
        int heightSpan = heightBand.maxY() - heightBand.minY() + 1;
        int offset = Math.min(heightSpan - 1, (int) Math.floor(this.hashUnit(x, z, HEIGHT_HASH_SALT) * heightSpan));
        return heightBand.minY() + offset;
    }

    private double hashUnit(int x, int z, long salt) {
        long hash = (((long) x) << 32) ^ (z & 0xffffffffL) ^ salt;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (hash >>> 11) * 0x1.0p-53D;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private record HeightBand(int minY, int maxY) {
    }
}
