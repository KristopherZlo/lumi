package io.github.lumi.gametest;

import java.io.IOException;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.world.level.gamerules.GameRules;

/** Creates the deterministic clean world shared by client behavior scenarios. */
final class LumiClientBehaviorWorld {
    private LumiClientBehaviorWorld() { }

    static void run(
            ClientGameTestContext context,
            String scenarioName,
            Scenario scenario) {
        try (LumiBehaviorReport report = LumiBehaviorReport.create(
                FabricLoader.getInstance().getGameDir(), scenarioName)) {
            long started = System.nanoTime();
            try (TestSingleplayerContext singleplayer = context.worldBuilder()
                    .setUseConsistentSettings(false)
                    .adjustSettings(LumiClientBehaviorWorld::configureWorld)
                    .create()) {
                context.setScreen(() -> null);
                context.waitForScreen(null);
                singleplayer.getClientWorld().waitForChunksRender();
                report.event("stage", "world_create", "succeeded", 0,
                        elapsedMillis(started), "seed=710 mode=creative");
                context.takeScreenshot("lumi-" + scenarioName + "-world-created");
                scenario.run(context, singleplayer, report);
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot write Lumi behavior report", failed);
        }
    }

    private static void configureWorld(WorldCreationUiState settings) {
        settings.setName("Lumi behavior seed 710");
        settings.setSeed("710");
        settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
        settings.setAllowCommands(true);
        settings.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
        settings.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
        settings.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
        settings.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, null);
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    @FunctionalInterface
    interface Scenario {
        void run(
                ClientGameTestContext context,
                TestSingleplayerContext singleplayer,
                LumiBehaviorReport report) throws IOException;
    }
}
