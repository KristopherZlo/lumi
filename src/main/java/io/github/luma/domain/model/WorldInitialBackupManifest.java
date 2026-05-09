package io.github.luma.domain.model;

import java.time.Instant;
import java.util.Map;

public record WorldInitialBackupManifest(
        int schemaVersion,
        String levelName,
        long seed,
        String classifier,
        long maxCompressedBytes,
        Map<String, DimensionBackupSummary> dimensions,
        Instant startedAt,
        Instant completedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 2;

    public WorldInitialBackupManifest {
        levelName = levelName == null ? "" : levelName;
        classifier = classifier == null ? "" : classifier;
        maxCompressedBytes = Math.max(0L, maxCompressedBytes);
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
    }

    public WorldInitialBackupManifest(
            int schemaVersion,
            String levelName,
            long seed,
            String classifier,
            Map<String, DimensionBackupSummary> dimensions,
            Instant startedAt,
            Instant completedAt
    ) {
        this(schemaVersion, levelName, seed, classifier, 0L, dimensions, startedAt, completedAt);
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
            int skippedVisitedOnlyChunks,
            int skippedByBudgetChunks,
            long compressedBytes,
            boolean storageBudgetExceeded
    ) {

        public DimensionBackupSummary(
                String dimensionId,
                int scannedChunks,
                int backedUpChunks,
                int skippedPristineChunks,
                long compressedBytes
        ) {
            this(dimensionId, scannedChunks, backedUpChunks, skippedPristineChunks, 0, 0, compressedBytes, false);
        }

        public DimensionBackupSummary {
            dimensionId = dimensionId == null ? "" : dimensionId;
            scannedChunks = Math.max(0, scannedChunks);
            backedUpChunks = Math.max(0, backedUpChunks);
            skippedPristineChunks = Math.max(0, skippedPristineChunks);
            skippedVisitedOnlyChunks = Math.max(0, skippedVisitedOnlyChunks);
            skippedByBudgetChunks = Math.max(0, skippedByBudgetChunks);
            compressedBytes = Math.max(0L, compressedBytes);
        }
    }
}
