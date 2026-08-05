package io.github.lumi.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Focused exact-state Restore and branch-switch correctness suite. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiExactRestoreClientGameTest
        implements FabricClientGameTest {
    private LumiExactRestoreScenario.PreparedExact prepared;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.EXACT)) {
            return;
        }
        try (var ignored = LumiUiScaleTestScope.readableViewport()) {
            LumiClientBehaviorWorld.runWithReopen(
                    context, "exact-restore",
                    (test, world, report) -> prepared =
                            new LumiExactRestoreScenario(test, world, report)
                                    .run(new LumiUiTestDriver(test)),
                    (test, world, report) ->
                            new LumiExactRestoreScenario(test, world, report)
                                    .verifyReopened(prepared));
        }
    }
}
