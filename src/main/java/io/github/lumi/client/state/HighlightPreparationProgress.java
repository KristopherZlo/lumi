package io.github.lumi.client.state;

/** Immutable local work progress for preparing exact Compare geometry and visible uploads. */
public record HighlightPreparationProgress(long completed, long total) {
    public static final HighlightPreparationProgress NONE = new HighlightPreparationProgress(0, 0);

    public HighlightPreparationProgress {
        if (completed < 0 || total < completed) {
            throw new IllegalArgumentException("Invalid highlight preparation progress");
        }
    }

    public boolean preparing() {
        return completed < total;
    }

    public double fraction() {
        return total == 0 ? 0 : completed / (double) total;
    }

    public int percent() {
        return (int) Math.floor(fraction() * 100);
    }
}
