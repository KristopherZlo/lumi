package io.github.lumi.gametest;

import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Performs Latest to Initial as the first mutation in one fresh measurement JVM. */
final class LumiColdRestoreMeasurementScenario {
    private static final String PREFIX = LumiHistoryBenchmarkConfig.PREFIX;
    private final ClientGameTestContext context;
    private final TestSingleplayerContext singleplayer;
    private final LumiBehaviorReport report;
    private final LumiBehaviorOperations operations;
    private final LumiUiTestDriver ui;

    LumiColdRestoreMeasurementScenario(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            LumiBehaviorReport report) {
        this.context = context;
        this.singleplayer = singleplayer;
        this.report = report;
        operations = new LumiBehaviorOperations(
                context, singleplayer.getServer(), report);
        ui = new LumiUiTestDriver(context);
    }

    void run() throws IOException {
        LumiColdRestoreManifest manifest = LumiColdRestoreManifest.read(
                requiredPath("coldManifest"));
        if (manifest.fixtureDigest().isBlank()) {
            throw new IllegalArgumentException(
                    "Cold fixture manifest has no filesystem digest");
        }
        String openedWorld = System.getProperty(PREFIX + "existingWorld", "");
        if (!manifest.worldName().equals(openedWorld)) {
            throw new AssertionError("Cold fixture world mismatch: expected "
                    + manifest.worldName() + ", got " + openedWorld);
        }
        long pid = ProcessHandle.current().pid();
        long jvmStarted = ManagementFactory.getRuntimeMXBean().getStartTime();
        report.event("process", "fresh_jvm", "started", 0, 0,
                "pid=" + pid + ";jvmStartMillis=" + jvmStarted
                        + ";fixtureDigest=" + manifest.fixtureDigest()
                        + ";priorRestores=0");

        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        assertValue("fixture_latest_branch",
                operations.activeBranch().value(), manifest.latestBranch().value());
        assertValue("fixture_latest_head",
                operations.activeCommit().hex(), manifest.latestCommit().hex());

        LumiRestoreMeasurement measurement = operations.measureRestore(
                "fresh-jvm-latest-to-initial", manifest.initialCommit());
        report.event("restore_metrics", "fresh-jvm-latest-to-initial", "measured", 0,
                measurement.totalMillis(), measurement.describe());

        assertValue("restored_branch",
                operations.activeBranch().value(), manifest.latestBranch().value());
        assertValue("restored_head",
                operations.activeCommit().hex(), manifest.initialCommit().hex());
        LumiBehaviorChecks checks =
                new LumiBehaviorChecks(context, singleplayer, report);
        LumiWorldSnapshot restored = checks.snapshot(
                "fresh_jvm_initial_endpoint", List.of(manifest.area()));
        assertValue("restored_exact_digest",
                restored.sha256(), manifest.initialDigest());
        writeResult(requiredPath("coldResult"), manifest, measurement, pid, jvmStarted);
        report.event("gate", "fresh_jvm_cold_sample", "succeeded", 0,
                measurement.totalMillis(),
                "firstMutation=true;exact=true;pid=" + pid);
    }

    private static void writeResult(
            Path path,
            LumiColdRestoreManifest manifest,
            LumiRestoreMeasurement measurement,
            long pid,
            long jvmStarted) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Properties values = new Properties();
        values.setProperty("schema", "1");
        values.setProperty("pid", Long.toString(pid));
        values.setProperty("jvmStartMillis", Long.toString(jvmStarted));
        values.setProperty("fixtureDigest", manifest.fixtureDigest());
        values.setProperty("priorRestores", "0");
        values.setProperty("exact", "true");
        values.setProperty("expectedBlocks", Long.toString(manifest.blockCount()));
        values.setProperty("expectedChunks", Long.toString(manifest.chunkCount()));
        values.setProperty("changedBlocks",
                Long.toString(measurement.apply().changedBlocks()));
        values.setProperty("loadedChunks",
                Long.toString(measurement.apply().loadedChunks()));
        values.setProperty("storedChunks",
                Long.toString(measurement.apply().storedChunks()));
        values.setProperty("confirmationToClientAckMillis",
                Long.toString(measurement.totalMillis()));
        values.setProperty("confirmationToEnqueueMillis",
                Long.toString(measurement.queueMillis()));
        values.setProperty("enqueueToTerminalMillis",
                Long.toString(measurement.serverMillis()));
        values.setProperty("maximumServerTickNanos",
                Long.toString(measurement.maximumServerTickNanos()));
        try (Writer writer = Files.newBufferedWriter(
                absolute, StandardCharsets.UTF_8)) {
            values.store(writer, "Lumi fresh-JVM cold Restore sample");
        }
    }

    private void assertValue(String name, String actual, String expected) {
        if (!actual.equals(expected)) {
            report.event("assertion", name, "failed", 0, 0,
                    "expected=" + expected + ";actual=" + actual);
            throw new AssertionError(name + " expected " + expected
                    + " but was " + actual);
        }
        report.event("assertion", name, "succeeded", 0, 0, actual);
    }

    private static Path requiredPath(String suffix) {
        String value = System.getProperty(PREFIX + suffix);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(PREFIX + suffix + " is required");
        }
        return Path.of(value);
    }
}
