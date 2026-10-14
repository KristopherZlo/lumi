package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Revisioned pointer to the workspace whose branch and scope are active. */
public record ActiveWorkspace(UUID id, long revision) {
    public ActiveWorkspace {
        Objects.requireNonNull(id, "id");
        if (revision < 0) {
            throw new IllegalArgumentException("Active workspace revision cannot be negative");
        }
    }
}
