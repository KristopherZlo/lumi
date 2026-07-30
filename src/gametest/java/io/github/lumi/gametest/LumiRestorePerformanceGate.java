package io.github.lumi.gametest;

import java.util.List;
import java.util.ArrayList;
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
                .mapToLong(LumiRestoreMeasurement::totalMillis).max().orElseThrow();
        long maximumExtraHeap = measurements.stream()
                .mapToLong(LumiRestoreMeasurement::extraHeapBytes).max().orElseThrow();
        long maximumTick = measurements.stream()
                .mapToLong(LumiRestoreMeasurement::maximumServerTickNanos)
                .max().orElseThrow();
        long loadedChunks = measurements.stream()
                .mapToLong(measurement -> measurement.apply().loadedChunks()).sum();
        long storedChunks = measurements.stream()
                .mapToLong(measurement -> measurement.apply().storedChunks()).sum();

        List<String> violations = new ArrayList<>();
        if (loadedChunks == 0 && storedChunks == 0) {
            violations.add("Restore benchmark exercised no chunk apply path");
        }
        if (config.chunkPath().requiresUnloadedFixture()) {
            long coldStoredChunks = measurements.getFirst().apply().storedChunks();
            if (coldStoredChunks < config.fixtureChunks()) {
                violations.add("Cold Restore benchmark applied "
                        + coldStoredChunks + " stored chunks; expected at least "
                        + config.fixtureChunks() + " fixture chunks");
            }
        }
        requireAtMost(violations,
                "additional Restore heap", maximumExtraHeap, MAX_EXTRA_HEAP);
        if (maximumTick == 0) {
            violations.add("Restore benchmark observed no complete server tick");
        }
        requireAtMost(violations, "server tick", maximumTick, MAX_SERVER_TICK);
        if (config.layers() == 16 && config.baseSize() == 512) {
            requireAtMost(violations,
                    "512x512x16 application p50", applicationP50,
                    TimeUnit.MILLISECONDS.toNanos(750));
            requireAtMost(violations,
                    "512x512x16 full Restore", maximumTotalMillis, 3_000);
        } else if (config.layers() == 16 && config.baseSize() == 1_000) {
            requireAtMost(violations,
                    "1000x1000x16 application p50", applicationP50,
                    TimeUnit.SECONDS.toNanos(2));
        } else if (config.layers() == 16 && config.baseSize() == 5_000) {
            requireAtMost(violations,
                    "5000x5000x16 full Restore", maximumTotalMillis, 60_000);
        }
        if (!violations.isEmpty()) {
            throw new AssertionError(String.join("; ", violations));
        }
        return new Result(applicationP50, maximumTotalMillis,
                maximumExtraHeap, maximumTick, loadedChunks, storedChunks);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private static void requireAtMost(
            List<String> violations,
            String name,
            long actual,
            long maximum) {
        if (actual > maximum) {
            violations.add(name + " exceeded: actual=" + actual
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
