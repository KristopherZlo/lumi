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
        long seed,
        ChunkPath chunkPath) {
    static final String PREFIX = "lumi.benchmark.";
    static final String ENABLED_PROPERTY = PREFIX + "enabled";
    private static final long MAX_LIVE_FIXTURE_CHUNKS = 1_089;

    LumiHistoryBenchmarkConfig {
        requireRange("baseSize", baseSize, 16, 10_000);
        requireRange("changeSize", changeSize, 16, baseSize);
        requireRange("layers", layers, 1, 64);
        requireRange("commits", commits, 1, 1_000);
        requireRange("restoreSamples", restoreSamples, 1, 1_002);
        requireRange("measureEvery", measureEvery, 1, commits);
        if (chunkPath == null) {
            throw new IllegalArgumentException("chunkPath is required");
        }
        if (chunkPath == ChunkPath.NATURAL && restoreSamples < 2) {
            throw new IllegalArgumentException(
                    "natural chunkPath requires at least two restoreSamples");
        }
        if (chunkPath == ChunkPath.STORED && restoreSamples != 1) {
            throw new IllegalArgumentException(
                    "stored chunkPath requires exactly one restore sample");
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static LumiHistoryBenchmarkConfig load() {
        ChunkPath chunkPath = ChunkPath.parse(System.getProperty(
                PREFIX + "chunkPath", "natural"));
        return new LumiHistoryBenchmarkConfig(
                integer("baseSize", 64),
                integer("changeSize", 32),
                integer("layers", 2),
                integer("commits", 3),
                integer("restoreSamples",
                        chunkPath.requiresUnloadedFixture() ? 1 : 4),
                integer("measureEvery", 1),
                Long.getLong(PREFIX + "seed", 710L),
                chunkPath);
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

    long fixtureChunks() {
        long chunksPerAxis = (baseSize + 15L) / 16L;
        return chunksPerAxis * chunksPerAxis;
    }

    String reportName() {
        return String.format(Locale.ROOT, "history-benchmark-%dx%dx%d-%d%s",
                baseSize, baseSize, layers, commits, chunkPath.reportSuffix());
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
                + ";chunkPath=" + chunkPath.propertyValue
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

    enum ChunkPath {
        NATURAL("natural"),
        STORED("stored");

        private final String propertyValue;

        ChunkPath(String propertyValue) {
            this.propertyValue = propertyValue;
        }

        static ChunkPath parse(String value) {
            for (ChunkPath path : values()) {
                if (path.propertyValue.equalsIgnoreCase(value)) {
                    return path;
                }
            }
            throw new IllegalArgumentException(
                    "chunkPath must be natural or stored, got " + value);
        }

        boolean requiresUnloadedFixture() {
            return this == STORED;
        }

        String reportSuffix() {
            return requiresUnloadedFixture() ? "-stored" : "";
        }
    }
}
