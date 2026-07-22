package io.github.lumi.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Explicitly enabled dense-history performance and repository-size benchmark. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiHistoryBenchmarkClientGameTest
        implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiHistoryBenchmarkConfig.enabled()) {
            return;
        }
        LumiHistoryBenchmarkConfig config = LumiHistoryBenchmarkConfig.load();
        config.requireRunnableFixture();
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.run(
                    context, config.reportName(), (test, world, report) ->
                            new LumiHistoryBenchmarkScenario(
                                    test, world, report, config)
                                    .run(new LumiUiTestDriver(test)));
        }
    }
}
