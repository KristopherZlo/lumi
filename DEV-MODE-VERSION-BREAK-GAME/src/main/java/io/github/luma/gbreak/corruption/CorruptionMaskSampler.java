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
        return this.noiseValue(pos.getX(), pos.getZ(), settings);
    }

    public double noiseValue(int blockX, int blockZ, CorruptionSettings settings) {
        double noiseScale = settings.noiseScale();
        double detailNoiseScale = settings.detailNoiseScale();
        double base = this.noiseSampler.sample(blockX * noiseScale, blockZ * noiseScale, 0.0D);
        double detail = this.noiseSampler.sample(
                1000.0D + blockX * detailNoiseScale,
                -1000.0D + blockZ * detailNoiseScale,
                531.0D
        );
        return base + detail * 0.35D;
    }

    public boolean isWorldMaskPosition(BlockPos pos, CorruptionSettings settings) {
        return this.isWorldMaskPosition(pos, this.noiseValue(pos, settings), settings);
    }

    public boolean isWorldMaskPosition(BlockPos pos, double noiseValue, CorruptionSettings settings) {
        return this.isWorldMaskColumn(pos.getX(), pos.getZ(), noiseValue, settings);
    }

    public boolean isWorldMaskColumn(int blockX, int blockZ, double noiseValue, CorruptionSettings settings) {
        double density = settings.noiseDensityPercent() / 100.0D;
        double normalizedNoise = this.clamp((noiseValue + 1.35D) / 2.7D, 0.0D, 1.0D);
        double clusteredDensity = density * (0.05D + Math.pow(normalizedNoise, 3.0D) * 8.0D);
        return this.columnHashUnit(blockX, blockZ) < this.clamp(clusteredDensity, 0.0002D, 0.95D);
    }

    public List<BlockPos> buildSurfaceOffsets(int horizontalRadius) {
        List<BlockPos> offsets = new ArrayList<>();
        for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
            for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                if (x == 0 && z == 0) {
                    continue;
                }
                offsets.add(new BlockPos(x, 0, z));
            }
        }
        return List.copyOf(offsets);
    }

    private double columnHashUnit(int blockX, int blockZ) {
        long hash = (((long) blockX) << 32) ^ (blockZ & 0xffffffffL) ^ WORLD_MASK_SEED;
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
