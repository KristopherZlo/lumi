package io.github.luma.baselinegametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

@SuppressWarnings("UnstableApiUsage")
public final class BaselineClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        BaselineChecks checks = new BaselineChecks();
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            singleplayer.getServer().runOnServer(server -> new BaselineGameplaySuite().run(server, checks));
            context.waitTicks(5);
            this.report(checks);
            context.takeScreenshot("lumi-baseline-client-gameplay");
        } catch (RuntimeException | Error exception) {
            this.report(checks);
            throw exception;
        } catch (Exception exception) {
            this.report(checks);
            throw new RuntimeException("Lumi baseline gameplay testing failed", exception);
        }
    }

    private void report(BaselineChecks checks) {
        String result = checks.failedCount() == 0 ? "passed" : "completed with failures";
        System.out.println("Lumi baseline gameplay testing " + result + ": "
                + checks.passedCount() + " passed, "
                + checks.failedCount() + " failed");
        for (String failure : checks.failures()) {
            System.out.println("Lumi baseline gameplay failure: " + failure);
        }
    }
}
