package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;

/** Stable hand-off from the fixture JVM to independent cold measurement JVMs. */
record LumiColdRestoreManifest(
        String worldName,
        BranchName initialBranch,
        CommitId initialCommit,
        BranchName latestBranch,
        CommitId latestCommit,
        BlockBox area,
        String initialDigest,
        String latestDigest,
        String fixtureDigest) {
    private static final String SCHEMA = "1";

    LumiColdRestoreManifest {
        requireText("worldName", worldName);
        Objects.requireNonNull(initialBranch, "initialBranch");
        Objects.requireNonNull(initialCommit, "initialCommit");
        Objects.requireNonNull(latestBranch, "latestBranch");
        Objects.requireNonNull(latestCommit, "latestCommit");
        Objects.requireNonNull(area, "area");
        requireText("initialDigest", initialDigest);
        requireText("latestDigest", latestDigest);
        Objects.requireNonNull(fixtureDigest, "fixtureDigest");
    }

    static LumiColdRestoreManifest capture(
            String worldName,
            LumiHistoryBenchmarkScenario.BranchFixture fixture) {
        return new LumiColdRestoreManifest(
                worldName,
                fixture.a().ref().name(),
                fixture.a().ref().commit(),
                fixture.b().ref().name(),
                fixture.b().ref().commit(),
                fixture.area(),
                fixture.a().snapshot().sha256(),
                fixture.b().snapshot().sha256(),
                "");
    }

    static LumiColdRestoreManifest read(Path path) throws IOException {
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(
                path, StandardCharsets.UTF_8)) {
            values.load(reader);
        }
        if (!SCHEMA.equals(values.getProperty("schema"))) {
            throw new IOException("Unsupported cold fixture manifest schema");
        }
        return new LumiColdRestoreManifest(
                required(values, "worldName"),
                new BranchName(required(values, "initialBranch")),
                commit(values, "initialCommit"),
                new BranchName(required(values, "latestBranch")),
                commit(values, "latestCommit"),
                area(required(values, "area")),
                required(values, "initialDigest"),
                required(values, "latestDigest"),
                values.getProperty("fixtureDigest", ""));
    }

    void write(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Properties values = new Properties();
        values.setProperty("schema", SCHEMA);
        values.setProperty("worldName", worldName);
        values.setProperty("initialBranch", initialBranch.value());
        values.setProperty("initialCommit", initialCommit.hex());
        values.setProperty("latestBranch", latestBranch.value());
        values.setProperty("latestCommit", latestCommit.hex());
        values.setProperty("area", area(area));
        values.setProperty("initialDigest", initialDigest);
        values.setProperty("latestDigest", latestDigest);
        if (!fixtureDigest.isBlank()) {
            values.setProperty("fixtureDigest", fixtureDigest);
        }
        try (Writer writer = Files.newBufferedWriter(
                absolute, StandardCharsets.UTF_8)) {
            values.store(writer, "Lumi cold Restore fixture");
        }
    }

    LumiColdRestoreManifest withFixtureDigest(String digest) {
        requireText("fixtureDigest", digest);
        return new LumiColdRestoreManifest(
                worldName, initialBranch, initialCommit, latestBranch, latestCommit,
                area, initialDigest, latestDigest, digest);
    }

    long blockCount() {
        return (long) (area.maxX() - area.minX() + 1)
                * (area.maxY() - area.minY() + 1)
                * (area.maxZ() - area.minZ() + 1);
    }

    long chunkCount() {
        long x = Math.floorDiv(area.maxX(), 16)
                - Math.floorDiv(area.minX(), 16) + 1L;
        long z = Math.floorDiv(area.maxZ(), 16)
                - Math.floorDiv(area.minZ(), 16) + 1L;
        return x * z;
    }

    private static CommitId commit(Properties values, String name) {
        return new CommitId(new ObjectId(required(values, name)));
    }

    private static BlockBox area(String value) {
        String[] parts = value.split(",", -1);
        if (parts.length != 6) {
            throw new IllegalArgumentException("Invalid cold fixture area: " + value);
        }
        int[] coordinate = java.util.Arrays.stream(parts)
                .mapToInt(Integer::parseInt).toArray();
        return new BlockBox(
                coordinate[0], coordinate[1], coordinate[2],
                coordinate[3], coordinate[4], coordinate[5]);
    }

    private static String area(BlockBox area) {
        return area.minX() + "," + area.minY() + "," + area.minZ() + ","
                + area.maxX() + "," + area.maxY() + "," + area.maxZ();
    }

    private static String required(Properties values, String name) {
        String value = values.getProperty(name);
        requireText(name, value);
        return value;
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
