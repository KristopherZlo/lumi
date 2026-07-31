package io.github.lumi.gametest;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
        OperationMode operation,
        Profile profile,
        boolean saveOnly) {
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
        if (profile == null) {
            throw new IllegalArgumentException("profile is required");
        }
        if (profile == Profile.PLAYER_SCALE_30
                && (baseSize != 512 || changeSize != 512 || layers != 16
                || commits != 30 || chunkPath != ChunkPath.NATURAL
                || operation != OperationMode.RESTORE)) {
            throw new IllegalArgumentException(
                    "player-scale-30 requires 512x512x16, 30 commits, "
                            + "natural chunks, and restore operation");
        }
        if (saveOnly && profile != Profile.PLAYER_SCALE_30) {
            throw new IllegalArgumentException(
                    "saveOnly requires player-scale-30");
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

    static Optional<List<CommitId>> restoreTargets() {
        String configured = System.getProperty(PREFIX + "restoreTargets");
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        List<CommitId> targets = Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> new CommitId(new ObjectId(value)))
                .toList();
        if (targets.isEmpty()) {
            throw new IllegalArgumentException(
                    "restoreTargets must contain at least one commit ID");
        }
        return Optional.of(targets);
    }

    static LumiHistoryBenchmarkConfig load() {
        Profile profile = Profile.parse(System.getProperty(
                PREFIX + "profile", "native"));
        OperationMode operation = operationMode();
        ChunkPath chunkPath = ChunkPath.parse(System.getProperty(
                PREFIX + "chunkPath", "natural"));
        if (profile == Profile.PLAYER_SCALE_30) {
            return new LumiHistoryBenchmarkConfig(
                    512, 512, 16, 30, 8, 5,
                    Long.getLong(PREFIX + "seed", 710L),
                    chunkPath, operation, profile,
                    Boolean.getBoolean(PREFIX + "saveOnly"));
        }
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
                operation,
                profile,
                false);
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
                operation.reportSuffix(), chunkPath.reportSuffix()
                        + profile.reportSuffix()
                        + (saveOnly ? "-save-only" : ""));
    }

    String describe() {
        return "fixture=" + profile.fixture()
                + ";baseSize=" + baseSize
                + ";changeSize=" + changeSize
                + ";layers=" + layers
                + ";commits=" + commits
                + ";restoreSamples=" + restoreSamples
                + ";measureEvery=" + measureEvery
                + ";seed=" + seed
                + ";chunkPath=" + chunkPath.propertyValue
                + operation.describeSuffix()
                + (saveOnly ? ";saveOnly=true" : "")
                + ";baseBlocks=" + baseBlocks()
                + (profile == Profile.PLAYER_SCALE_30
                        ? ";changedBlocksRange=1..4194304"
                        : ";changedBlocksPerCommit=" + changedBlocksPerCommit());
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

    enum Profile {
        NATIVE("native", "loaded-native-sections", ""),
        PLAYER_SCALE_30(
                "player-scale-30", "player-worldedit-scale-30",
                "-player-scale-30");

        private final String propertyValue;
        private final String fixture;
        private final String reportSuffix;

        Profile(String propertyValue, String fixture, String reportSuffix) {
            this.propertyValue = propertyValue;
            this.fixture = fixture;
            this.reportSuffix = reportSuffix;
        }

        static Profile parse(String value) {
            for (Profile profile : values()) {
                if (profile.propertyValue.equalsIgnoreCase(value)) {
                    return profile;
                }
            }
            throw new IllegalArgumentException(
                    "profile must be native or player-scale-30, got " + value);
        }

        String fixture() {
            return fixture;
        }

        String reportSuffix() {
            return reportSuffix;
        }
    }
}
