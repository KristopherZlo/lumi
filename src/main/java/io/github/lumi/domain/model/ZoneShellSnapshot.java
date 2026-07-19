package io.github.lumi.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable bounded result of one off-thread zone shell query. */
public record ZoneShellSnapshot(UUID workspaceId, List<ZoneShell> zones) {
    public ZoneShellSnapshot {
        Objects.requireNonNull(workspaceId, "workspaceId");
        zones = List.copyOf(Objects.requireNonNull(zones, "zones"));
    }

    public record ZoneShell(
            UUID id,
            String name,
            int color,
            long revision,
            boolean active,
            boolean entered,
            List<ZoneShellFace> faces) {
        public ZoneShell {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            faces = List.copyOf(Objects.requireNonNull(faces, "faces"));
            if (name.isBlank() || revision < 0) {
                throw new IllegalArgumentException(
                        "Invalid zone shell snapshot");
            }
        }
    }
}
