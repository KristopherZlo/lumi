package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Immutable zone metadata identity required to resume a scoped Restore. */
public record ZoneRestoreTarget(UUID workspaceId, UUID zoneId, long revision) {
    public ZoneRestoreTarget {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(zoneId, "zoneId");
        if (revision < 0) {
            throw new IllegalArgumentException("Zone Restore revision cannot be negative");
        }
    }
}
