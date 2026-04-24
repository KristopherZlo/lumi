package io.github.luma.gbreak.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class CorruptionRestoreWaveProgressTest {

    @Test
    void advancesRadiusByConfiguredSpeed() {
        CorruptionRestoreWaveProgress progress = new CorruptionRestoreWaveProgress();

        assertEquals(9L, progress.advanceAndAllowedDistanceSquared(3));
        assertEquals(3, progress.radiusBlocks());
        assertEquals(36L, progress.advanceAndAllowedDistanceSquared(3));
        assertEquals(6, progress.radiusBlocks());
    }

    @Test
    void resetStartsWaveAtCenterAgain() {
        CorruptionRestoreWaveProgress progress = new CorruptionRestoreWaveProgress();

        progress.advanceAndAllowedDistanceSquared(8);
        progress.reset();

        assertEquals(0, progress.radiusBlocks());
        assertEquals(4L, progress.advanceAndAllowedDistanceSquared(2));
    }
}
