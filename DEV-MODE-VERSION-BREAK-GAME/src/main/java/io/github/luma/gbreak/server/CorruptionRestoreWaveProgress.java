package io.github.luma.gbreak.server;

final class CorruptionRestoreWaveProgress {

    private int radiusBlocks;

    long advanceAndAllowedDistanceSquared(int blocksPerStep) {
        this.radiusBlocks += Math.max(1, blocksPerStep);
        return this.allowedDistanceSquared();
    }

    void reset() {
        this.radiusBlocks = 0;
    }

    int radiusBlocks() {
        return this.radiusBlocks;
    }

    private long allowedDistanceSquared() {
        return (long) this.radiusBlocks * (long) this.radiusBlocks;
    }
}
