package io.github.luma.gbreak.client;

final class RestoreFadeAlphaCurve {

    static final int DEFAULT_DURATION_TICKS = 96;
    private static final int MAX_ALPHA = 235;

    int alpha(int ageTicks) {
        if (ageTicks < 0) {
            return MAX_ALPHA;
        }
        if (ageTicks >= DEFAULT_DURATION_TICKS) {
            return 0;
        }

        float progress = (float) ageTicks / (float) DEFAULT_DURATION_TICKS;
        float easedProgress = progress * progress * (3.0F - 2.0F * progress);
        return Math.round(MAX_ALPHA * (1.0F - easedProgress));
    }
}
