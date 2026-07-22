package io.github.lumi.minecraft.world;

import java.util.concurrent.atomic.LongAdder;

/** Thread-safe measurements shared by all bounded batches of one apply session. */
final class RestoreApplyMetrics {
    private final LongAdder loadedChunks = new LongAdder();
    private final LongAdder storedChunks = new LongAdder();
    private final LongAdder sectionSwaps = new LongAdder();
    private final LongAdder changedBlocks = new LongAdder();
    private final LongAdder lightSections = new LongAdder();
    private final LongAdder fullChunkPackets = new LongAdder();
    private final LongAdder sectionPackets = new LongAdder();
    private final LongAdder packetPayloadBytes = new LongAdder();
    private final LongAdder chunkLoadNanos = new LongAdder();
    private final LongAdder loadedApplyNanos = new LongAdder();
    private final LongAdder storageReadNanos = new LongAdder();
    private final LongAdder storageWriteNanos = new LongAdder();
    private final LongAdder storageSyncNanos = new LongAdder();
    private final LongAdder verificationNanos = new LongAdder();

    void loadedSection(SectionApplyResult result) {
        if (result.changedCount() == 0) {
            return;
        }
        sectionSwaps.increment();
        changedBlocks.add(result.changedCount());
        if (result.lightChanged()) {
            lightSections.increment();
        }
    }

    void loadedChunk(ChunkSyncResult result) {
        loadedChunks.increment();
        fullChunkPackets.add(result.fullChunkPackets());
        sectionPackets.add(result.sectionPackets());
        packetPayloadBytes.add(result.payloadBytes());
    }

    void storedChunk(StoredChunkApplyResult result) {
        storedChunks.increment();
        storageReadNanos.add(result.readNanos() + result.verifyNanos());
        storageWriteNanos.add(result.writeNanos());
        storageSyncNanos.add(result.syncNanos());
    }

    void chunkLoad(long nanos) { chunkLoadNanos.add(nanos); }
    void loadedApply(long nanos) { loadedApplyNanos.add(nanos); }
    void verification(long nanos) { verificationNanos.add(nanos); }

    RestoreApplyStatistics snapshot() {
        return new RestoreApplyStatistics(
                loadedChunks.sum(), storedChunks.sum(), sectionSwaps.sum(),
                changedBlocks.sum(), lightSections.sum(), fullChunkPackets.sum(),
                sectionPackets.sum(), packetPayloadBytes.sum(), chunkLoadNanos.sum(),
                loadedApplyNanos.sum(), storageReadNanos.sum(), storageWriteNanos.sum(),
                storageSyncNanos.sum(), verificationNanos.sum());
    }
}
