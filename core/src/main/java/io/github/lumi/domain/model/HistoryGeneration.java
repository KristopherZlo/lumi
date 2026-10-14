package io.github.lumi.domain.model;

import java.util.Objects;
import java.util.UUID;

/** One isolated representation of a dimension's complete history. */
public record HistoryGeneration(UUID id, WorldDataVersion dataVersion) {
    public static final HistoryGeneration LEGACY = new HistoryGeneration(
            new UUID(0, 0), WorldDataVersion.LEGACY_12111);

    public HistoryGeneration {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dataVersion, "dataVersion");
        if (id.equals(new UUID(0, 0)) && !dataVersion.equals(WorldDataVersion.LEGACY_12111)) {
            throw new IllegalArgumentException("The original history uses Minecraft 1.21.11 data");
        }
    }

    public boolean legacy() {
        return id.equals(LEGACY.id());
    }
}
