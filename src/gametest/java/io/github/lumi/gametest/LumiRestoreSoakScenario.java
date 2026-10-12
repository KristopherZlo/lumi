package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/** Repeats immutable endpoints, then observes ordinary ticking without a freeze. */
final class LumiRestoreSoakScenario {
    private static final String PREFIX = LumiHistoryBenchmarkConfig.PREFIX;
    private final ClientGameTestContext context;
    private final TestSingleplayerContext world;
    private final LumiBehaviorReport report;
    private final LumiBehaviorOperations operations;
    private final LumiColdRestoreManifest manifest;
    private final int cycles;
    private final int observeSeconds;

    LumiRestoreSoakScenario(ClientGameTestContext context,
            TestSingleplayerContext world, LumiBehaviorReport report) throws IOException {
        this.context = context;
        this.world = world;
        this.report = report;
        operations = new LumiBehaviorOperations(context, world.getServer(), report);
        String path = System.getProperty(PREFIX + "coldManifest", "");
        if (path.isBlank()) throw new IllegalArgumentException("coldManifest is required for soak");
        manifest = LumiColdRestoreManifest.read(Path.of(path));
        cycles = Integer.getInteger(PREFIX + "soakCycles", 20);
        observeSeconds = Integer.getInteger(PREFIX + "observeSeconds", 1800);
        if (cycles < 1 || cycles > 1000 || observeSeconds < 1 || observeSeconds > 86400) {
            throw new IllegalArgumentException("Invalid soakCycles or observeSeconds");
        }
        if (!manifest.worldName().equals(System.getProperty(PREFIX + "existingWorld"))) {
            throw new IllegalArgumentException("Soak manifest belongs to another world");
        }
    }

    void run() throws IOException {
        LumiUiTestDriver ui = new LumiUiTestDriver(context);
        ui.completeOnboardingIfShown();
        ui.awaitHistory();
        ui.enableEntityRestore();
        for (int cycle = 1; cycle <= cycles; cycle++) {
            restore("soak-" + cycle + "-initial", true);
            restore("soak-" + cycle + "-latest", false);
        }
        // Wall-clock duration matters: client GameTest ticks can run faster than real play.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(observeSeconds);
        long nextSample = 0;
        while (System.nanoTime() < deadline) {
            context.waitTick();
            if (System.nanoTime() >= nextSample) {
                assertSettled();
                var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                var os = (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
                report.event("load", "post_restore", "sampled", 0, 0,
                        "processCpuNanos=" + os.getProcessCpuTime()
                                + ";heapUsedBytes=" + heap.getUsed()
                                + ";heapCommittedBytes=" + heap.getCommitted()
                                + ";fps=" + context.computeOnClient(client -> client.getFps()));
                nextSample = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            }
        }
        assertEndpoint(false);
        report.event("gate", "restore_soak", "succeeded", 0, 0,
                "cycles=" + cycles + ";observeSeconds=" + observeSeconds
                        + ";exactScope=" + manifest.area()
                        + ";foregroundGpuAndGameplay=unverified");
    }

    void verifyReopened() throws IOException {
        new LumiUiTestDriver(context).awaitHistory();
        assertEndpoint(false);
        assertSettled();
        report.event("gate", "restore_soak_reopen", "succeeded", 0, 0, "");
    }

    private void restore(String name, boolean initial) throws IOException {
        var target = initial ? manifest.initialCommit() : manifest.latestCommit();
        var measurement = operations.measureRestore(name, target);
        report.event("restore_metrics", name, "measured", 0,
                measurement.totalMillis(), measurement.describe());
        assertEndpoint(initial);
        assertSettled();
    }

    private void assertEndpoint(boolean initial) throws IOException {
        var target = initial ? manifest.initialCommit() : manifest.latestCommit();
        if (!operations.activeCommit().equals(target)) {
            throw new AssertionError("Soak did not publish " + target);
        }
        var actual = world.getServer().computeOnServer(server -> LumiWorldSnapshot.capture(
                server.getPlayerList().getPlayers().getFirst().level(), List.of(manifest.area())));
        String expected = initial ? manifest.initialDigest() : manifest.latestDigest();
        if (!actual.sha256().equals(expected)) {
            throw new AssertionError("Soak endpoint digest mismatch: " + actual.sha256());
        }
    }

    private void assertSettled() {
        world.getServer().runOnServer(server -> {
            var runtime = LumiMod.serverRuntime().find(
                    server.getPlayerList().getPlayers().getFirst().level()).orElseThrow();
            if (runtime.freeze().isFrozen() || runtime.operations().hasActiveOperation()
                    || runtime.recoveryJournal().isPresent()) {
                throw new AssertionError("Completed soak retained a mutation, freeze or journal");
            }
        });
    }
}
