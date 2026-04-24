package io.github.luma.gbreak.corruption;

import io.github.luma.gbreak.state.CorruptionSettings;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import net.minecraft.util.math.random.LocalRandom;

public final class CorruptionMaskSampler {

    private static final long WORLD_MASK_SEED = 0x4C554D41474C4954L;
    private final SimplexNoiseSampler noiseSampler = new SimplexNoiseSampler(new LocalRandom(WORLD_MASK_SEED));

    public int effectiveHorizontalRadius(int viewDistanceChunks, CorruptionSettings settings) {
        int renderDistanceBlocks = Math.max(2, viewDistanceChunks) * 16;
        return Math.max(16, renderDistanceBlocks * settings.renderRadiusPercent() / 100);
    }

    public double noiseValue(BlockPos pos, CorruptionSettings settings) {
        double noiseScale = settings.noiseScale();
        double detailNoiseScale = settings.detailNoiseScale();
        double base = this.noiseSampler.sample(pos.getX() * noiseScale, pos.getY() * noiseScale, pos.getZ() * noiseScale);
        double detail = this.noiseSampler.sample(
                1000.0D + pos.getX() * detailNoiseScale,
                -1000.0D + pos.getY() * detailNoiseScale,
                pos.getZ() * detailNoiseScale
        );
        return base + detail * 0.35D;
    }

    public boolean isWorldMaskPosition(BlockPos pos, CorruptionSettings settings) {
        return this.isWorldMaskPosition(pos, this.noiseValue(pos, settings), settings);
    }

    public boolean isWorldMaskPosition(BlockPos pos, double noiseValue, CorruptionSettings settings) {
        double density = settings.noiseDensityPercent() / 100.0D;
        double normalizedNoise = this.clamp((noiseValue + 1.35D) / 2.7D, 0.0D, 1.0D);
        double clusteredDensity = density * (0.05D + Math.pow(normalizedNoise, 3.0D) * 8.0D);
        return this.positionHashUnit(pos) < this.clamp(clusteredDensity, 0.0002D, 0.95D);
    }

    public List<BlockPos> buildSearchOffsets(int horizontalRadius, int verticalRadius) {
        List<BlockPos> offsets = new ArrayList<>();
        int horizontalStep = this.horizontalStep(horizontalRadius);
        for (int y = -verticalRadius; y <= verticalRadius; y++) {
            for (int x = -horizontalRadius; x <= horizontalRadius; x += horizontalStep) {
                for (int z = -horizontalRadius; z <= horizontalRadius; z += horizontalStep) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    offsets.add(new BlockPos(x, y, z));
                }
            }
        }
        return List.copyOf(offsets);
    }

    private int horizontalStep(int horizontalRadius) {
        return Math.max(1, horizontalRadius / 32);
    }

    private double positionHashUnit(BlockPos pos) {
        long hash = pos.asLong() ^ WORLD_MASK_SEED;
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (hash >>> 11) * 0x1.0p-53D;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
