package io.github.lumi.domain.model;

import java.util.Objects;

/** One directional visible-world change between two immutable commits. */
public record BlockChange(int x, int y, int z, Kind kind) {
    public BlockChange {
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        ADDED,
        REMOVED,
        CHANGED
    }
}
