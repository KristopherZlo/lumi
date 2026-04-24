package io.github.luma.gbreak.client;

final class RestoreFadeAlphaCurve {

    static final int DEFAULT_DURATION_TICKS = 120;
    private static final int MAX_ALPHA = 235;

    int alpha(int ageTicks) {
        return this.alpha(ageTicks, DEFAULT_DURATION_TICKS);
    }

    int alpha(int ageTicks, int durationTicks) {
        int safeDurationTicks = Math.max(1, durationTicks);
        if (ageTicks < 0) {
            return MAX_ALPHA;
        }
        if (ageTicks >= safeDurationTicks) {
            return 0;
        }

        float progress = (float) ageTicks / (float) safeDurationTicks;
        float easedProgress = progress * progress * (3.0F - 2.0F * progress);
        return Math.round(MAX_ALPHA * (1.0F - easedProgress));
    }
}
