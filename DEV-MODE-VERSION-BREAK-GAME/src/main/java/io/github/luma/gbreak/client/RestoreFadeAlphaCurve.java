package io.github.luma.gbreak.client;

final class RestoreFadeAlphaCurve {

    static final int DEFAULT_DURATION_TICKS = 36;
    private static final int MAX_ALPHA = 220;

    int alpha(int ageTicks) {
        if (ageTicks < 0) {
            return MAX_ALPHA;
        }
        if (ageTicks >= DEFAULT_DURATION_TICKS) {
            return 0;
        }

        float progress = (float) ageTicks / (float) DEFAULT_DURATION_TICKS;
        float remaining = 1.0F - progress;
        return Math.round(MAX_ALPHA * remaining * remaining);
    }
}
