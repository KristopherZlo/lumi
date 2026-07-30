package io.github.lumi.minecraft.world;

import java.util.Map;

/** Immutable counters for one target or safe-return world application. */
public record RestoreApplyStatistics(
        long loadedChunks,
        long storedChunks,
        Map<StoredChunkApplyResult.Outcome, Long> storedFallbacks,
        long sectionSwaps,
        long changedBlocks,
        long lightSections,
        long fullChunkPackets,
        long sectionPackets,
        long packetPayloadBytes,
        long batchPreparationNanos,
        long lightingNanos,
        long chunkLoadNanos,
        long loadedApplyNanos,
        long storageReadNanos,
        long storageWriteNanos,
        long storageSyncNanos,
        long verificationNanos) {
    public RestoreApplyStatistics {
        storedFallbacks = Map.copyOf(storedFallbacks);
        for (long value : new long[] {
                loadedChunks, storedChunks, sectionSwaps, changedBlocks, lightSections,
                fullChunkPackets, sectionPackets, packetPayloadBytes,
                batchPreparationNanos, lightingNanos, chunkLoadNanos, loadedApplyNanos,
                storageReadNanos, storageWriteNanos, storageSyncNanos, verificationNanos}) {
            if (value < 0) {
                throw new IllegalArgumentException("Restore statistics cannot be negative");
            }
        }
    }

    public static final RestoreApplyStatistics EMPTY = new RestoreApplyStatistics(
            0, 0, Map.of(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
