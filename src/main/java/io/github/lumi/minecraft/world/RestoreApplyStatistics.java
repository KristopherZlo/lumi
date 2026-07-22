package io.github.lumi.minecraft.world;

/** Immutable counters for one target or safe-return world application. */
public record RestoreApplyStatistics(
        long loadedChunks,
        long storedChunks,
        long sectionSwaps,
        long changedBlocks,
        long lightSections,
        long fullChunkPackets,
        long sectionPackets,
        long packetPayloadBytes,
        long chunkLoadNanos,
        long loadedApplyNanos,
        long storageReadNanos,
        long storageWriteNanos,
        long storageSyncNanos,
        long verificationNanos) {
    public static final RestoreApplyStatistics EMPTY = new RestoreApplyStatistics(
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
}
