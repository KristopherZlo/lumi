package io.github.lumi.gametest;

import java.util.List;
import java.util.concurrent.TimeUnit;

/** Enforces the opt-in dense Restore latency, heap, and server-tick budgets. */
final class LumiRestorePerformanceGate {
    private static final long MAX_EXTRA_HEAP = 1L << 30;
    private static final long MAX_SERVER_TICK = TimeUnit.MILLISECONDS.toNanos(50);

    private LumiRestorePerformanceGate() { }

    static Result verify(
            LumiHistoryBenchmarkConfig config,
            List<LumiRestoreMeasurement> measurements) {
        if (measurements.isEmpty()) {
            throw new AssertionError("Restore benchmark produced no measurements");
        }
        long applicationP50 = median(measurements.stream()
                .mapToLong(LumiRestoreMeasurement::applicationNanos)
                .boxed().toList());
        long maximumTotalMillis = measurements.stream()
                .mapToLong(LumiRestoreMeasurement::operationMillis).max().orElseThrow();
        long maximumExtraHeap = measurements.stream()
                .mapToLong(LumiRestoreMeasurement::extraHeapBytes).max().orElseThrow();
        long maximumTick = measurements.stream()
                .mapToLong(LumiRestoreMeasurement::maximumServerTickNanos)
                .max().orElseThrow();
        long loadedChunks = measurements.stream()
                .mapToLong(measurement -> measurement.apply().loadedChunks()).sum();
        long storedChunks = measurements.stream()
                .mapToLong(measurement -> measurement.apply().storedChunks()).sum();

        if (loadedChunks == 0 && storedChunks == 0) {
            throw new AssertionError("Restore benchmark exercised no chunk apply path");
        }
        requireAtMost("additional Restore heap", maximumExtraHeap, MAX_EXTRA_HEAP);
        if (maximumTick == 0) {
            throw new AssertionError("Restore benchmark observed no complete server tick");
        }
        requireAtMost("server tick", maximumTick, MAX_SERVER_TICK);
        if (config.layers() == 16 && config.baseSize() == 512) {
            requireAtMost("512x512x16 application p50", applicationP50,
                    TimeUnit.MILLISECONDS.toNanos(750));
            requireAtMost("512x512x16 full Restore", maximumTotalMillis, 3_000);
        } else if (config.layers() == 16 && config.baseSize() == 1_000) {
            requireAtMost("1000x1000x16 application p50", applicationP50,
                    TimeUnit.SECONDS.toNanos(2));
        } else if (config.layers() == 16 && config.baseSize() == 5_000) {
            requireAtMost("5000x5000x16 full Restore", maximumTotalMillis, 60_000);
        }
        return new Result(applicationP50, maximumTotalMillis,
                maximumExtraHeap, maximumTick, loadedChunks, storedChunks);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static void requireAtMost(String name, long actual, long maximum) {
        if (actual > maximum) {
            throw new AssertionError(name + " exceeded: actual=" + actual
                    + ", maximum=" + maximum);
        }
    }

    record Result(
            long applicationP50Nanos,
            long maximumTotalMillis,
            long maximumExtraHeapBytes,
            long maximumServerTickNanos,
            long loadedChunks,
            long storedChunks) {
        String describe() {
            return "applicationP50Ms="
                    + TimeUnit.NANOSECONDS.toMillis(applicationP50Nanos)
                    + ";maximumTotalMs=" + maximumTotalMillis
                    + ";maximumExtraHeapBytes=" + maximumExtraHeapBytes
                    + ";maximumServerTickMs="
                    + TimeUnit.NANOSECONDS.toMillis(maximumServerTickNanos)
                    + ";chunkPathCoverage="
                    + LumiRestoreMeasurement.chunkPath(loadedChunks, storedChunks)
                    + ";loadedChunks=" + loadedChunks
                    + ";storedChunks=" + storedChunks;
        }
    }
}
