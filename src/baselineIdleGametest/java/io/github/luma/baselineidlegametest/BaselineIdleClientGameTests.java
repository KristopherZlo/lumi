package io.github.luma.baselineidlegametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

@SuppressWarnings("UnstableApiUsage")
public final class BaselineIdleClientGameTests implements FabricClientGameTest {

    private static final int IDLE_TICKS = 20;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            context.waitTicks(IDLE_TICKS);
            this.report();
            context.takeScreenshot("lumi-baseline-idle-client-smoke");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi baseline idle client gametest failed", exception);
        }
    }

    private void report() {
        boolean lumiAbsent = !FabricLoader.getInstance().isModLoaded("lumi");
        String result = lumiAbsent ? "passed" : "completed with failures";
        int passed = lumiAbsent ? 1 : 0;
        int failed = lumiAbsent ? 0 : 1;
        System.out.println("Lumi baseline idle startup testing " + result + ": "
                + passed + " passed, " + failed + " failed");
        if (!lumiAbsent) {
            throw new AssertionError("Lumi mod was loaded in baseline idle startup test");
        }
    }
}
