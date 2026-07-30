package io.github.lumi.gametest;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Explicitly enabled dense-history performance and repository-size benchmark. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiHistoryBenchmarkClientGameTest
        implements FabricClientGameTest {
    private static final String EXISTING_WORLD_PROPERTY =
            LumiHistoryBenchmarkConfig.PREFIX + "existingWorld";
    private static final String COLD_MODE_PROPERTY =
            LumiHistoryBenchmarkConfig.PREFIX + "coldMode";
    private static final String COLD_MANIFEST_PROPERTY =
            LumiHistoryBenchmarkConfig.PREFIX + "coldManifest";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.BENCHMARK)
                || !LumiHistoryBenchmarkConfig.enabled()) {
            return;
        }
        String coldMode = System.getProperty(COLD_MODE_PROPERTY, "").trim();
        if ("fixture".equalsIgnoreCase(coldMode)) {
            runColdFixture(context);
            return;
        }
        if ("measure".equalsIgnoreCase(coldMode)) {
            runColdMeasurement(context);
            return;
        }
        if (!coldMode.isEmpty()) {
            throw new IllegalArgumentException(
                    "Unknown cold benchmark mode: " + coldMode);
        }
        String existingWorld = System.getProperty(EXISTING_WORLD_PROPERTY);
        if (existingWorld != null && !existingWorld.isBlank()) {
            try (var ignored = LumiUiScaleTestScope.readableViewport()) {
                if (LumiHistoryBenchmarkConfig.operationMode()
                        == LumiHistoryBenchmarkConfig.OperationMode.BRANCH_SWITCH) {
                    runExistingBranchSwitch(context, existingWorld);
                } else {
                    LumiClientBehaviorWorld.runExisting(
                            context, "history-benchmark-existing-world",
                            existingWorld, (test, world, report) ->
                                    new LumiExistingWorldRestoreScenario(
                                            test, world, report).run());
                }
            }
            return;
        }
        LumiHistoryBenchmarkConfig config = LumiHistoryBenchmarkConfig.load();
        config.requireRunnableFixture();
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            if (config.operation()
                    == LumiHistoryBenchmarkConfig.OperationMode.BRANCH_SWITCH) {
                runBranchSwitchBenchmark(context, config);
                return;
            }
            LumiClientBehaviorWorld.run(
                    context, config.reportName(), (test, world, report) ->
                            new LumiHistoryBenchmarkScenario(
                                    test, world, report, config)
                                    .run(new LumiUiTestDriver(test)));
        }
    }

    private static void runColdMeasurement(ClientGameTestContext context) {
        String existingWorld = System.getProperty(EXISTING_WORLD_PROPERTY);
        if (existingWorld == null || existingWorld.isBlank()) {
            throw new IllegalArgumentException(
                    EXISTING_WORLD_PROPERTY + " is required for measurement mode");
        }
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.runExisting(
                    context, "cold-restore-fresh-jvm", existingWorld,
                    (test, world, report) ->
                            new LumiColdRestoreMeasurementScenario(
                                    test, world, report).run());
        }
    }

    private static void runColdFixture(ClientGameTestContext context) {
        LumiHistoryBenchmarkConfig config = LumiHistoryBenchmarkConfig.load();
        config.requireRunnableFixture();
        String manifest = System.getProperty(COLD_MANIFEST_PROPERTY);
        if (manifest == null || manifest.isBlank()) {
            throw new IllegalArgumentException(
                    COLD_MANIFEST_PROPERTY + " is required for fixture mode");
        }
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.run(
                    context, "cold-restore-fixture", (test, world, report) -> {
                        LumiHistoryBenchmarkScenario.BranchFixture fixture =
                                new LumiHistoryBenchmarkScenario(
                                        test, world, report, config)
                                        .prepareBranchSwitch(
                                                new LumiUiTestDriver(test));
                        LumiColdRestoreManifest captured =
                                LumiColdRestoreManifest.capture(
                                        "Lumi behavior seed 710", fixture);
                        captured.write(Path.of(manifest));
                        report.event("fixture", "cold_restore_manifest",
                                "written", 0, 0,
                                "initial=" + captured.initialCommit().hex()
                                        + ";latest=" + captured.latestCommit().hex()
                                        + ";latestBranch="
                                        + captured.latestBranch().value());
                    });
        }
    }

    private static void runBranchSwitchBenchmark(
            ClientGameTestContext context,
            LumiHistoryBenchmarkConfig config) {
        var fixture = new AtomicReference<
                LumiHistoryBenchmarkScenario.BranchFixture>();
        LumiClientBehaviorWorld.runWithReopen(
                context, config.reportName(),
                (test, world, report) -> fixture.set(
                        new LumiHistoryBenchmarkScenario(
                                test, world, report, config)
                                .prepareBranchSwitch(
                                        new LumiUiTestDriver(test))),
                (test, world, report) -> new LumiHistoryBenchmarkScenario(
                        test, world, report, config)
                        .runBranchSwitch(
                                new LumiUiTestDriver(test),
                                fixture.get()));
    }

    private static void runExistingBranchSwitch(
            ClientGameTestContext context,
            String existingWorld) {
        var fixture = new AtomicReference<
                LumiExistingWorldBranchSwitchScenario.Fixture>();
        LumiClientBehaviorWorld.runExistingWithReopen(
                context, "history-benchmark-existing-world-branch-switch",
                existingWorld,
                (test, world, report) -> fixture.set(
                        new LumiExistingWorldBranchSwitchScenario(
                                test, world, report).prepare()),
                (test, world, report) ->
                        new LumiExistingWorldBranchSwitchScenario(
                                test, world, report).run(fixture.get()));
    }
}
