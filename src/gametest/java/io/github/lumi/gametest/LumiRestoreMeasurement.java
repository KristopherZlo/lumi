package io.github.lumi.gametest;

import io.github.lumi.minecraft.world.RestoreApplyStatistics;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/** End-to-end and apply-path measurements for one durably published Restore. */
record LumiRestoreMeasurement(
        long totalMillis,
        long queueMillis,
        long serverMillis,
        long heapBeforeBytes,
        long peakHeapBytes,
        long maximumServerTickNanos,
        RestoreApplyStatistics apply) {
    LumiRestoreMeasurement {
        Objects.requireNonNull(apply, "apply");
        if (totalMillis < 0 || queueMillis < 0 || serverMillis < 0
                || totalMillis < queueMillis || totalMillis < serverMillis
                || heapBeforeBytes < 0 || peakHeapBytes < heapBeforeBytes
                || maximumServerTickNanos < 0) {
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

    String chunkPath() {
        return chunkPath(apply.loadedChunks(), apply.storedChunks());
    }

    static String chunkPath(long loadedChunks, long storedChunks) {
        if (loadedChunks > 0 && storedChunks > 0) {
            return "mixed";
        }
        if (loadedChunks > 0) {
            return "loaded-only";
        }
        if (storedChunks > 0) {
            return "stored-only";
        }
        return "none";
    }

    String describe() {
        return "totalMs=" + totalMillis
                + ";queueMs=" + queueMillis
                + ";serverMs=" + serverMillis
                + ";applicationMs=" + TimeUnit.NANOSECONDS.toMillis(applicationNanos())
                + ";extraHeapBytes=" + extraHeapBytes()
                + ";maximumServerTickMs=" + millis(maximumServerTickNanos)
                + ";chunkPath=" + chunkPath()
                + ";loadedChunks=" + apply.loadedChunks()
                + ";storedChunks=" + apply.storedChunks()
                + ";storedFallbacks=" + apply.storedFallbacks()
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
