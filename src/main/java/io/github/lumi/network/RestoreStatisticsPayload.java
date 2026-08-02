package io.github.lumi.network;

import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;

/** Bounded Restore measurements attached only to a terminal operation event. */
public record RestoreStatisticsPayload(
        long loadedChunks,
        long storedChunks,
        long chunkWrites,
        long poiWrites,
        long entityWrites,
        long chunkRegions,
        long poiRegions,
        long entityRegions,
        long changedBlocks,
        long lightSections,
        long batchPreparationNanos,
        long lightingNanos,
        long chunkLoadNanos,
        long loadedApplyNanos,
        long storageReadNanos,
        long storageWriteNanos,
        long storageSyncNanos,
        long storageForceNanos,
        long verificationNanos) {
    public RestoreStatisticsPayload {
        for (long value : new long[] {
                loadedChunks, storedChunks, chunkWrites, poiWrites, entityWrites,
                chunkRegions, poiRegions, entityRegions, changedBlocks, lightSections,
                batchPreparationNanos, lightingNanos, chunkLoadNanos,
                loadedApplyNanos, storageReadNanos, storageWriteNanos,
                storageSyncNanos, storageForceNanos, verificationNanos}) {
            if (value < 0) {
                throw new IllegalArgumentException(
                        "Restore statistics cannot be negative");
            }
        }
    }

    public static RestoreStatisticsPayload from(RestoreApplyStatistics statistics) {
        Objects.requireNonNull(statistics, "statistics");
        return new RestoreStatisticsPayload(
                statistics.loadedChunks(), statistics.storedChunks(),
                statistics.chunkWrites(), statistics.poiWrites(),
                statistics.entityWrites(), statistics.chunkRegions(),
                statistics.poiRegions(), statistics.entityRegions(),
                statistics.changedBlocks(), statistics.lightSections(),
                statistics.batchPreparationNanos(), statistics.lightingNanos(),
                statistics.chunkLoadNanos(), statistics.loadedApplyNanos(),
                statistics.storageReadNanos(), statistics.storageWriteNanos(),
                statistics.storageSyncNanos(), statistics.storageForceNanos(),
                statistics.verificationNanos());
    }

    void write(FriendlyByteBuf buffer) {
        buffer.writeVarLong(loadedChunks);
        buffer.writeVarLong(storedChunks);
        buffer.writeVarLong(chunkWrites);
        buffer.writeVarLong(poiWrites);
        buffer.writeVarLong(entityWrites);
        buffer.writeVarLong(chunkRegions);
        buffer.writeVarLong(poiRegions);
        buffer.writeVarLong(entityRegions);
        buffer.writeVarLong(changedBlocks);
        buffer.writeVarLong(lightSections);
        buffer.writeVarLong(batchPreparationNanos);
        buffer.writeVarLong(lightingNanos);
        buffer.writeVarLong(chunkLoadNanos);
        buffer.writeVarLong(loadedApplyNanos);
        buffer.writeVarLong(storageReadNanos);
        buffer.writeVarLong(storageWriteNanos);
        buffer.writeVarLong(storageSyncNanos);
        buffer.writeVarLong(storageForceNanos);
        buffer.writeVarLong(verificationNanos);
    }

    static RestoreStatisticsPayload read(FriendlyByteBuf buffer) {
        return new RestoreStatisticsPayload(
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong(), buffer.readVarLong(),
                buffer.readVarLong());
    }
}
