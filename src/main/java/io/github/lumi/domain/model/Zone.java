package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Project-scoped metadata for an overlapping set of 16-cubed world cells. */
public record Zone(
        UUID id,
        UUID workspaceId,
        String name,
        int color,
        Set<SectionKey> cells,
        Set<UUID> activeActors,
        long revision) {
    public Zone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(name, "name");
        cells = Set.copyOf(Objects.requireNonNull(cells, "cells"));
        activeActors = Set.copyOf(Objects.requireNonNull(activeActors, "activeActors"));
        if (name.isBlank() || name.length() > 256
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Zone name must be 1-256 visible characters");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("Zone revision cannot be negative");
        }
    }

    public Zone(
            UUID id,
            UUID workspaceId,
            String name,
            int color,
            Set<SectionKey> cells,
            Set<UUID> activeActors) {
        this(id, workspaceId, name, color, cells, activeActors, 0);
    }
}
