package io.github.lumi.domain.model;

/** Material totals on both sides of a comparison. */
public record MaterialDelta(long before, long after) {
    public MaterialDelta {
        if (before < 0 || after < 0) {
            throw new IllegalArgumentException("Material totals cannot be negative");
        }
    }

    public long change() {
        return Math.subtractExact(after, before);
    }
}
