package io.github.luma.baselineidlegametest;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;

@SuppressWarnings("UnstableApiUsage")
public final class BaselineIdleClientGameTests implements FabricClientGameTest {

    private static final int IDLE_TICKS = 20;
    private static final long WORLD_SEED = 6840143426479848331L;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed(Long.toString(WORLD_SEED)))
                .create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            context.waitTicks(IDLE_TICKS);
            long actualSeed = singleplayer.getServer().computeOnServer(server -> server.overworld().getSeed());
            this.report(actualSeed);
            context.takeScreenshot("lumi-baseline-idle-client-smoke");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi baseline idle client gametest failed", exception);
        }
    }

    private void report(long actualSeed) {
        boolean lumiAbsent = !FabricLoader.getInstance().isModLoaded("lumi");
        boolean seedMatches = actualSeed == WORLD_SEED;
        String result = lumiAbsent && seedMatches ? "passed" : "completed with failures";
        int passed = (lumiAbsent ? 1 : 0) + (seedMatches ? 1 : 0);
        int failed = 2 - passed;
        System.out.println("Lumi baseline idle startup seed: expected=" + WORLD_SEED + ", actual=" + actualSeed);
        System.out.println("Lumi baseline idle startup testing " + result + ": "
                + passed + " passed, " + failed + " failed");
        if (!lumiAbsent) {
            throw new AssertionError("Lumi mod was loaded in baseline idle startup test");
        }
        if (!seedMatches) {
            throw new AssertionError("Baseline idle startup world used a different seed: " + actualSeed);
        }
    }
}
