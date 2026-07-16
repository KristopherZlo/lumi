package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** A named history view and optional block boundary over one dimension repository. */
public record Workspace(
        UUID id,
        String name,
        Optional<BlockBox> bounds,
        WorkspaceSettings settings) {
    public Workspace {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        bounds = Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(settings, "settings");
        if (name.isBlank() || name.length() > 256
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Workspace name must be 1-256 visible characters");
        }
    }
}
