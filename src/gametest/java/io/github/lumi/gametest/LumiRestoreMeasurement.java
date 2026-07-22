package io.github.lumi.gametest;

import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.util.concurrent.TimeUnit;

/** End-to-end and apply-path measurements for one durably published Restore. */
record LumiRestoreMeasurement(
        long totalMillis,
        long heapBeforeBytes,
        long peakHeapBytes,
        RestoreApplyStatistics apply) {
    LumiRestoreMeasurement {
        if (totalMillis < 0 || heapBeforeBytes < 0 || peakHeapBytes < heapBeforeBytes) {
            throw new IllegalArgumentException("Invalid Restore measurement");
        }
    }

    long extraHeapBytes() {
        return peakHeapBytes - heapBeforeBytes;
    }

    long applicationNanos() {
        return apply.loadedApplyNanos()
                + apply.storageWriteNanos()
                + apply.storageSyncNanos();
    }

    String describe() {
        return "totalMs=" + totalMillis
                + ";applicationMs=" + TimeUnit.NANOSECONDS.toMillis(applicationNanos())
                + ";extraHeapBytes=" + extraHeapBytes()
                + ";loadedChunks=" + apply.loadedChunks()
                + ";storedChunks=" + apply.storedChunks()
                + ";sectionSwaps=" + apply.sectionSwaps()
                + ";changedBlocks=" + apply.changedBlocks()
                + ";fullChunkPackets=" + apply.fullChunkPackets()
                + ";sectionPackets=" + apply.sectionPackets()
                + ";packetPayloadBytes=" + apply.packetPayloadBytes()
                + ";chunkLoadMs=" + millis(apply.chunkLoadNanos())
                + ";loadedApplyMs=" + millis(apply.loadedApplyNanos())
                + ";storageReadMs=" + millis(apply.storageReadNanos())
                + ";storageWriteMs=" + millis(apply.storageWriteNanos())
                + ";storageSyncMs=" + millis(apply.storageSyncNanos())
                + ";verificationMs=" + millis(apply.verificationNanos());
    }

    private static long millis(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
