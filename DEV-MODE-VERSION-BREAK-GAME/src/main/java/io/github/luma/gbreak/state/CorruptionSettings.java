package io.github.luma.gbreak.state;

public final class CorruptionSettings {

    private static final CorruptionSettings INSTANCE = new CorruptionSettings();

    private volatile int targetCorruptedBlocks = 96;
    private volatile int applyBatchSize = 24;
    private volatile int restoreBatchSize = 144;
    private volatile int renderRadiusPercent = 100;
    private volatile int verticalRadius = 5;
    private volatile double noiseScale = 0.115D;
    private volatile double detailNoiseScale = 0.31D;
    private volatile int particleBurstsPerTick = 14;
    private volatile int maxSkyDisplays = 96;

    private CorruptionSettings() {
    }

    public static CorruptionSettings getInstance() {
        return INSTANCE;
    }

    public int targetCorruptedBlocks() {
        return this.targetCorruptedBlocks;
    }

    public void setTargetCorruptedBlocks(int targetCorruptedBlocks) {
        this.targetCorruptedBlocks = this.clamp(targetCorruptedBlocks, 8, 256);
    }

    public int applyBatchSize() {
        return this.applyBatchSize;
    }

    public void setApplyBatchSize(int applyBatchSize) {
        this.applyBatchSize = this.clamp(applyBatchSize, 1, 96);
    }

    public int restoreBatchSize() {
        return this.restoreBatchSize;
    }

    public void setRestoreBatchSize(int restoreBatchSize) {
        this.restoreBatchSize = this.clamp(restoreBatchSize, 16, 512);
    }

    public int renderRadiusPercent() {
        return this.renderRadiusPercent;
    }

    public void setRenderRadiusPercent(int renderRadiusPercent) {
        this.renderRadiusPercent = this.clamp(renderRadiusPercent, 25, 100);
    }

    public int verticalRadius() {
        return this.verticalRadius;
    }

    public void setVerticalRadius(int verticalRadius) {
        this.verticalRadius = this.clamp(verticalRadius, 1, 12);
    }

    public double noiseScale() {
        return this.noiseScale;
    }

    public void setNoiseScale(double noiseScale) {
        this.noiseScale = this.clamp(noiseScale, 0.04D, 0.25D);
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
