package io.github.lumi.gametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Verifies crash producers before they can halt without reaching the completion entrypoint. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiPackagedOriginGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        if (System.getProperty("lumi.gametest.artifactHash") != null) {
            LumiClientCompletionGameTest.verifyLoadedOrigin();
        }
    }
}
