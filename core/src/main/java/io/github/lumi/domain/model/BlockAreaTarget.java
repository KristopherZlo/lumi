package io.github.lumi.domain.model;

import java.util.Objects;

/** Immutable partial-Restore block scope stored in the operation journal. */
public record BlockAreaTarget(BlockBox area, boolean outside) {
    public BlockAreaTarget {
        Objects.requireNonNull(area, "area");
    }
}
