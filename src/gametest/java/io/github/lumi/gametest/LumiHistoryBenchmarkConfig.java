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
        ChunkPath chunkPath,
        OperationMode operation) {
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
        if (operation == null) {
            throw new IllegalArgumentException("operation is required");
        }
        if (operation == OperationMode.BRANCH_SWITCH) {
            if (restoreSamples != 3) {
                throw new IllegalArgumentException(
                        "branch-switch operation requires exactly three samples");
            }
        } else if (chunkPath == ChunkPath.NATURAL && restoreSamples < 2) {
            throw new IllegalArgumentException(
                    "natural chunkPath requires at least two restoreSamples");
        } else if (chunkPath == ChunkPath.STORED && restoreSamples != 1) {
            throw new IllegalArgumentException(
                    "stored chunkPath requires exactly one restore sample");
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    static OperationMode operationMode() {
        return OperationMode.parse(System.getProperty(
                PREFIX + "operation", "restore"));
    }

    static LumiHistoryBenchmarkConfig load() {
        OperationMode operation = operationMode();
        ChunkPath chunkPath = ChunkPath.parse(System.getProperty(
                PREFIX + "chunkPath", "natural"));
        return new LumiHistoryBenchmarkConfig(
                integer("baseSize", 64),
                integer("changeSize", 32),
                integer("layers", 2),
                integer("commits", 3),
                integer("restoreSamples",
                        operation == OperationMode.BRANCH_SWITCH ? 3
                                : chunkPath.requiresUnloadedFixture() ? 1 : 4),
                integer("measureEvery", 1),
                Long.getLong(PREFIX + "seed", 710L),
                chunkPath,
                operation);
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
        return String.format(Locale.ROOT, "history-benchmark-%dx%dx%d-%d%s%s",
                baseSize, baseSize, layers, commits,
                operation.reportSuffix(), chunkPath.reportSuffix());
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
                + operation.describeSuffix()
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

    enum OperationMode {
        RESTORE("restore", ""),
        BRANCH_SWITCH("branch-switch", "-branch-switch");

        private final String propertyValue;
        private final String reportSuffix;

        OperationMode(String propertyValue, String reportSuffix) {
            this.propertyValue = propertyValue;
            this.reportSuffix = reportSuffix;
        }

        static OperationMode parse(String value) {
            for (OperationMode operation : values()) {
                if (operation.propertyValue.equalsIgnoreCase(value)) {
                    return operation;
                }
            }
            throw new IllegalArgumentException(
                    "operation must be restore or branch-switch, got " + value);
        }

        String reportSuffix() {
            return reportSuffix;
        }

        String describeSuffix() {
            return this == RESTORE ? "" : ";operation=" + propertyValue;
        }
    }
}
