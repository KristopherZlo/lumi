package io.github.lumi.gametest;

import java.util.Locale;

/** Validated system-property configuration for the opt-in history benchmark. */
record LumiHistoryBenchmarkConfig(
        int baseSize,
        int changeSize,
        int layers,
        int commits,
        int restoreSamples,
        int measureEvery,
        long seed) {
    static final String PREFIX = "lumi.benchmark.";
    static final String ENABLED_PROPERTY = PREFIX + "enabled";
    private static final long MAX_LIVE_FIXTURE_CHUNKS = 1_089;

    LumiHistoryBenchmarkConfig {
        requireRange("baseSize", baseSize, 16, 10_000);
        requireRange("changeSize", changeSize, 16, baseSize);
        requireRange("layers", layers, 1, 64);
        requireRange("commits", commits, 1, 1_000);
        requireRange("restoreSamples", restoreSamples, 2, 1_002);
        requireRange("measureEvery", measureEvery, 1, commits);
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static LumiHistoryBenchmarkConfig load() {
        return new LumiHistoryBenchmarkConfig(
                integer("baseSize", 64),
                integer("changeSize", 32),
                integer("layers", 2),
                integer("commits", 3),
                integer("restoreSamples", 4),
                integer("measureEvery", 1),
                Long.getLong(PREFIX + "seed", 710L));
    }

    void requireRunnableFixture() {
        long chunksPerAxis = (baseSize + 30L) / 16L;
        long chunks = chunksPerAxis * chunksPerAxis;
        if (chunks > MAX_LIVE_FIXTURE_CHUNKS) {
            throw new IllegalArgumentException(
                    "Dense benchmark would FULL-load about " + chunks
                            + " chunks. The live fixture is limited to "
                            + MAX_LIVE_FIXTURE_CHUNKS
                            + "; use a bounded stored-chunk fixture for this profile.");
        }
    }

    long baseBlocks() {
        return (long) baseSize * baseSize * layers;
    }

    long changedBlocksPerCommit() {
        return (long) changeSize * changeSize * layers;
    }

    String reportName() {
        return String.format(Locale.ROOT, "history-benchmark-%dx%dx%d-%d",
                baseSize, baseSize, layers, commits);
    }

    String describe() {
        return "fixture=loaded-native-sections"
                + ";baseSize=" + baseSize
                + ";changeSize=" + changeSize
                + ";layers=" + layers
                + ";commits=" + commits
                + ";restoreSamples=" + restoreSamples
                + ";measureEvery=" + measureEvery
                + ";seed=" + seed
                + ";baseBlocks=" + baseBlocks()
                + ";changedBlocksPerCommit=" + changedBlocksPerCommit();
    }

    private static int integer(String name, int fallback) {
        return Integer.getInteger(PREFIX + name, fallback);
    }

    private static void requireRange(
            String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between "
                    + minimum + " and " + maximum + ", got " + value);
        }
    }
}
