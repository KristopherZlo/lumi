package io.github.lumi.gametest;

import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Explicitly enabled dense-history performance and repository-size benchmark. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiHistoryBenchmarkClientGameTest
        implements FabricClientGameTest {
    private static final String EXISTING_WORLD_PROPERTY =
            LumiHistoryBenchmarkConfig.PREFIX + "existingWorld";

    @Override
    public void runTest(ClientGameTestContext context) {
        if (LumiClientBehaviorWorld.firstMinuteOnly()
                || !LumiHistoryBenchmarkConfig.enabled()) {
            return;
        }
        String existingWorld = System.getProperty(EXISTING_WORLD_PROPERTY);
        if (existingWorld != null && !existingWorld.isBlank()) {
            if (LumiHistoryBenchmarkConfig.operationMode()
                    != LumiHistoryBenchmarkConfig.OperationMode.RESTORE) {
                throw new IllegalArgumentException(
                        "Existing-world benchmark supports only restore");
            }
            try (var ignored = LumiUiScaleTestScope.readableViewport()) {
                LumiClientBehaviorWorld.runExisting(
                        context, "history-benchmark-existing-world",
                        existingWorld, (test, world, report) ->
                                new LumiExistingWorldRestoreScenario(
                                        test, world, report).run());
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
}
