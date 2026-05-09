package io.github.luma.domain.model;

import java.time.Instant;
import java.util.Map;

public record WorldInitialBackupManifest(
        int schemaVersion,
        String levelName,
        long seed,
        String classifier,
        Map<String, DimensionBackupSummary> dimensions,
        Instant startedAt,
        Instant completedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorldInitialBackupManifest {
        levelName = levelName == null ? "" : levelName;
        classifier = classifier == null ? "" : classifier;
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    }

    public boolean completedForSeed(long expectedSeed) {
        return this.schemaVersion >= CURRENT_SCHEMA_VERSION
                && this.seed == expectedSeed
                && this.completedAt != null;
    }

    public record DimensionBackupSummary(
            String dimensionId,
            int scannedChunks,
            int backedUpChunks,
            int skippedPristineChunks,
            long compressedBytes
    ) {

        public DimensionBackupSummary {
            dimensionId = dimensionId == null ? "" : dimensionId;
        }
    }
}
