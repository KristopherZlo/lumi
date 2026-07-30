package io.github.lumi.minecraft.world;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/** Thread-safe measurements shared by all bounded batches of one apply session. */
final class RestoreApplyMetrics {
    private final LongAdder loadedChunks = new LongAdder();
    private final LongAdder storedChunks = new LongAdder();
    private final Map<StoredChunkApplyResult.Outcome, LongAdder> storedFallbacks =
            new EnumMap<>(StoredChunkApplyResult.Outcome.class);
    private final LongAdder sectionSwaps = new LongAdder();
    private final LongAdder changedBlocks = new LongAdder();
    private final LongAdder lightSections = new LongAdder();
    private final LongAdder fullChunkPackets = new LongAdder();
    private final LongAdder sectionPackets = new LongAdder();
    private final LongAdder packetPayloadBytes = new LongAdder();
    private final LongAdder batchPreparationNanos = new LongAdder();
    private final LongAdder lightingNanos = new LongAdder();
    private final LongAdder chunkLoadNanos = new LongAdder();
    private final LongAdder loadedApplyNanos = new LongAdder();
    private final LongAdder storageReadNanos = new LongAdder();
    private final LongAdder storageWriteNanos = new LongAdder();
    private final LongAdder storageSyncNanos = new LongAdder();
    private final LongAdder verificationNanos = new LongAdder();

    RestoreApplyMetrics() {
        for (StoredChunkApplyResult.Outcome outcome
                : StoredChunkApplyResult.Outcome.values()) {
            if (outcome != StoredChunkApplyResult.Outcome.APPLIED) {
                storedFallbacks.put(outcome, new LongAdder());
            }
        }
    }

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
        if (!result.applied()) {
            storedFallbacks.get(result.outcome()).increment();
            return;
        }
        storedChunks.increment();
        sectionSwaps.add(result.sectionSwaps());
        changedBlocks.add(result.changedBlocks());
        lightSections.add(result.lightSections());
        storageReadNanos.add(result.readNanos());
        storageWriteNanos.add(result.writeNanos());
        storageSyncNanos.add(result.syncNanos());
        verificationNanos.add(result.verifyNanos());
    }

    void chunkLoad(long nanos) { chunkLoadNanos.add(nanos); }
    void batchPreparation(long nanos) { batchPreparationNanos.add(nanos); }
    void lighting(long nanos) { lightingNanos.add(nanos); }
    void loadedApply(long nanos) { loadedApplyNanos.add(nanos); }
    void verification(long nanos) { verificationNanos.add(nanos); }

    void persistence(WorldPersistenceSession.Timings timings) {
        storageWriteNanos.add(timings.writeNanos());
        storageSyncNanos.add(timings.syncNanos());
        verificationNanos.add(timings.verificationNanos());
    }

    RestoreApplyStatistics snapshot() {
        return new RestoreApplyStatistics(
                loadedChunks.sum(), storedChunks.sum(), storedFallbacks.entrySet().stream()
                        .filter(entry -> entry.getValue().sum() != 0)
                        .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                Map.Entry::getKey, entry -> entry.getValue().sum())),
                sectionSwaps.sum(),
                changedBlocks.sum(), lightSections.sum(), fullChunkPackets.sum(),
                sectionPackets.sum(), packetPayloadBytes.sum(),
                batchPreparationNanos.sum(), lightingNanos.sum(),
                chunkLoadNanos.sum(), loadedApplyNanos.sum(), storageReadNanos.sum(),
                storageWriteNanos.sum(), storageSyncNanos.sum(), verificationNanos.sum());
    }
}
