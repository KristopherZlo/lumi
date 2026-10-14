package io.github.lumi.domain.service;

import java.util.Objects;

/** Immutable progress emitted while a captured Save is published off-thread. */
public record SavePublicationProgress(String phase, long completed, long total) {
    public SavePublicationProgress {
        Objects.requireNonNull(phase, "phase");
        if (phase.isBlank()) {
            throw new IllegalArgumentException("Save progress phase must not be blank");
        }
        if (completed < 0 || total < 0 || total == 0 && completed != 0
                || total > 0 && completed > total) {
            throw new IllegalArgumentException("Invalid Save progress count");
        }
    }

    public static SavePublicationProgress indeterminate(String phase) {
        return new SavePublicationProgress(phase, 0, 0);
    }
}
