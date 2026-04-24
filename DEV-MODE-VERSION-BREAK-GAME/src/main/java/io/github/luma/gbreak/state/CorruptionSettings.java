package io.github.luma.gbreak.state;

public final class CorruptionSettings {

    private static final CorruptionSettings INSTANCE = new CorruptionSettings();

    private volatile double noiseDensityPercent = 22.1D;
    private volatile int applyBatchSize = 512;
    private volatile int restoreBatchSize = 512;
    private volatile int cleanupIntervalTicks = 4;
    private volatile int cleanupSpreadBlocksPerStep = 6;
    private volatile int restoreFadeDurationTicks = 120;
    private volatile int renderRadiusPercent = 100;
    private volatile double noiseScale = 0.055D;
    private volatile double detailNoiseScale = 0.18D;
    private volatile int particleBurstsPerTick = 14;
    private volatile int maxSkyDisplays = 96;

    private CorruptionSettings() {
    }

    public static CorruptionSettings getInstance() {
        return INSTANCE;
    }

    public double noiseDensityPercent() {
        return this.noiseDensityPercent;
    }

    public void setNoiseDensityPercent(double noiseDensityPercent) {
        this.noiseDensityPercent = this.clamp(noiseDensityPercent, 0.1D, 65.0D);
    }

    public int applyBatchSize() {
        return this.applyBatchSize;
    }

    public void setApplyBatchSize(int applyBatchSize) {
        this.applyBatchSize = this.clamp(applyBatchSize, 1, 1024);
    }

    public int restoreBatchSize() {
        return this.restoreBatchSize;
    }

    public void setRestoreBatchSize(int restoreBatchSize) {
        this.restoreBatchSize = this.clamp(restoreBatchSize, 16, 512);
    }

    public int cleanupIntervalTicks() {
        return this.cleanupIntervalTicks;
    }

    public void setCleanupIntervalTicks(int cleanupIntervalTicks) {
        this.cleanupIntervalTicks = this.clamp(cleanupIntervalTicks, 1, 20);
    }

    public int cleanupSpreadBlocksPerStep() {
        return this.cleanupSpreadBlocksPerStep;
    }

    public void setCleanupSpreadBlocksPerStep(int cleanupSpreadBlocksPerStep) {
        this.cleanupSpreadBlocksPerStep = this.clamp(cleanupSpreadBlocksPerStep, 1, 32);
    }

    public int restoreFadeDurationTicks() {
        return this.restoreFadeDurationTicks;
    }

    public void setRestoreFadeDurationTicks(int restoreFadeDurationTicks) {
        this.restoreFadeDurationTicks = this.clamp(restoreFadeDurationTicks, 20, 240);
    }

    public int renderRadiusPercent() {
        return this.renderRadiusPercent;
    }

    public void setRenderRadiusPercent(int renderRadiusPercent) {
        this.renderRadiusPercent = this.clamp(renderRadiusPercent, 25, 100);
    }

    public double noiseScale() {
        return this.noiseScale;
    }

    public void setNoiseScale(double noiseScale) {
        this.noiseScale = this.clamp(noiseScale, 0.01D, 0.18D);
    }

    public double detailNoiseScale() {
        return this.detailNoiseScale;
    }

    public void setDetailNoiseScale(double detailNoiseScale) {
        this.detailNoiseScale = this.clamp(detailNoiseScale, 0.08D, 0.55D);
    }

    public int particleBurstsPerTick() {
        return this.particleBurstsPerTick;
    }

    public void setParticleBurstsPerTick(int particleBurstsPerTick) {
        this.particleBurstsPerTick = this.clamp(particleBurstsPerTick, 0, 40);
    }

    public int maxSkyDisplays() {
        return this.maxSkyDisplays;
    }

    public void setMaxSkyDisplays(int maxSkyDisplays) {
        this.maxSkyDisplays = this.clamp(maxSkyDisplays, 0, 160);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
