package io.github.luma.minecraft.bootstrap;

final class WorldBootstrapDelay {

    private static final int MINIMUM_DELAY_TICKS = 20 * 10;
    private static final int QUIET_WINDOW_TICKS = 20 * 5;

    private int playerTicks;
    private int quietTicks;

    boolean tick(boolean chunkLoadingActive) {
        this.playerTicks += 1;
        this.quietTicks = chunkLoadingActive ? 0 : this.quietTicks + 1;
        return this.playerTicks >= MINIMUM_DELAY_TICKS
                && this.quietTicks >= QUIET_WINDOW_TICKS;
    }

    void reset() {
        this.playerTicks = 0;
        this.quietTicks = 0;
    }
}
