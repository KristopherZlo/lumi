package io.github.lumi.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Clean-world entry point for the large TNT and durable-entity workflow. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiStressBehaviorClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        LumiClientBehaviorWorld.run(context, "tnt-entities-stress",
                (test, world, report) ->
                        new LumiStressBehaviorScenario(test, world, report).run());
    }
}
