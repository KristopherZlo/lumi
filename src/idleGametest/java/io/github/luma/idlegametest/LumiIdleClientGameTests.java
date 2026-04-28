package io.github.luma.idlegametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

@SuppressWarnings("UnstableApiUsage")
public final class LumiIdleClientGameTests implements FabricClientGameTest {

    private static final int IDLE_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            context.waitTicks(IDLE_TICKS);
            this.report();
            context.takeScreenshot("lumi-idle-client-smoke");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi idle client gametest failed", exception);
        }
    }

    private void report() {
        boolean lumiLoaded = FabricLoader.getInstance().isModLoaded("lumi");
        String result = lumiLoaded ? "passed" : "completed with failures";
        int passed = lumiLoaded ? 1 : 0;
        int failed = lumiLoaded ? 0 : 1;
        System.out.println("Lumi idle startup testing " + result + ": "
                + passed + " passed, " + failed + " failed");
        if (!lumiLoaded) {
            throw new AssertionError("Lumi mod was not loaded in idle startup test");
        }
    }
}
