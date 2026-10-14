package io.github.lumi.domain.model;

import java.util.Objects;

/** The original and isolated target of one restartable history conversion. */
public record HistoryConversion(HistoryGeneration source, HistoryGeneration target) {
    public HistoryConversion {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        if (target.legacy() || source.id().equals(target.id())) {
            throw new IllegalArgumentException("History conversion requires a distinct target generation");
        }
    }
}
