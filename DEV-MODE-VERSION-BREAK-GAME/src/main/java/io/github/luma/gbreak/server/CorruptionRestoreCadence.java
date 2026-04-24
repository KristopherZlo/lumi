package io.github.luma.gbreak.server;

final class CorruptionRestoreCadence {

    private int ticksUntilNextBatch;

    boolean shouldRestoreNow(int intervalTicks) {
        int safeInterval = Math.max(1, intervalTicks);
        if (this.ticksUntilNextBatch > 0) {
            this.ticksUntilNextBatch--;
            return false;
        }

        this.ticksUntilNextBatch = safeInterval - 1;
        return true;
    }

    void reset() {
        this.ticksUntilNextBatch = 0;
    }
}
