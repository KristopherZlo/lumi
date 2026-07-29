package io.github.lumi.gametest;

import com.moulberry.axiom.Axiom;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.impl.client.gametest.world.TestWorldSaveImpl;
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
        run(context, scenarioName, scenario, null);
    }

    static void runWithReopen(
            ClientGameTestContext context,
            String scenarioName,
            Scenario scenario,
            Scenario reopenedScenario) {
        run(context, scenarioName, scenario, reopenedScenario);
    }

    static void runExisting(
            ClientGameTestContext context,
            String scenarioName,
            String saveName,
            Scenario scenario) {
        runExisting(context, scenarioName, saveName, scenario, null);
    }

    static void runExistingWithReopen(
            ClientGameTestContext context,
            String scenarioName,
            String saveName,
            Scenario scenario,
            Scenario reopenedScenario) {
        runExisting(
                context, scenarioName, saveName, scenario, reopenedScenario);
    }

    private static void runExisting(
            ClientGameTestContext context,
            String scenarioName,
            String saveName,
            Scenario scenario,
            Scenario reopenedScenario) {
        Path relative = Path.of(saveName);
        if (saveName.isBlank() || relative.isAbsolute()
                || saveName.equals(".") || saveName.equals("..")
                || relative.getNameCount() != 1) {
            throw new IllegalArgumentException(
                    "Existing test world must be one save-folder name");
        }
        Path saveDirectory = context.computeOnClient(client ->
                client.getLevelSource().getBaseDir().resolve(relative));
        if (!Files.isDirectory(saveDirectory)) {
            throw new IllegalArgumentException(
                    "Existing test world does not exist: " + saveDirectory);
        }
        try (LumiBehaviorReport report = LumiBehaviorReport.create(
                FabricLoader.getInstance().getGameDir(), scenarioName)) {
            TestWorldSave worldSave =
                    new TestWorldSaveImpl(context, saveDirectory);
            try (TestSingleplayerContext singleplayer = worldSave.open()) {
                prepareClient(context, singleplayer, false);
                scenario.run(context, singleplayer, report);
            }
            if (reopenedScenario != null) {
                long started = System.nanoTime();
                try (TestSingleplayerContext reopened = worldSave.open()) {
                    prepareClient(context, reopened, false);
                    report.event("stage", "world_reopen", "succeeded", 0,
                            elapsedMillis(started), "");
                    reopenedScenario.run(context, reopened, report);
                }
            }
            report.assertNoRuntimeFailures();
        } catch (IOException failed) {
            throw new IllegalStateException(
                    "Cannot run Lumi existing-world behavior test", failed);
        }
    }

    private static void run(
            ClientGameTestContext context,
            String scenarioName,
            Scenario scenario,
            Scenario reopenedScenario) {
        try (LumiBehaviorReport report = LumiBehaviorReport.create(
                FabricLoader.getInstance().getGameDir(), scenarioName)) {
            long started = System.nanoTime();
            TestWorldSave worldSave;
            try (TestSingleplayerContext singleplayer = context.worldBuilder()
                    .setUseConsistentSettings(false)
                    .adjustSettings(LumiClientBehaviorWorld::configureWorld)
                    .create()) {
                worldSave = singleplayer.getWorldSave();
                prepareClient(context, singleplayer, true);
                report.event("stage", "world_create", "succeeded", 0,
                        elapsedMillis(started), "seed=710 mode=creative");
                context.takeScreenshot("lumi-" + scenarioName + "-world-created");
                scenario.run(context, singleplayer, report);
            }
            if (reopenedScenario != null) {
                started = System.nanoTime();
                try (TestSingleplayerContext reopened = worldSave.open()) {
                    prepareClient(context, reopened, true);
                    report.event("stage", "world_reopen", "succeeded", 0,
                            elapsedMillis(started), "");
                    reopenedScenario.run(context, reopened, report);
                }
                report.assertNoRuntimeFailures();
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot write Lumi behavior report", failed);
        }
    }

    static boolean firstMinuteOnly() {
        return Boolean.getBoolean("lumi.gametest.firstMinuteOnly");
    }

    static void configureWorld(WorldCreationUiState settings) {
        settings.setName("Lumi behavior seed 710");
        settings.setSeed("710");
        settings.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
        settings.setAllowCommands(true);
        settings.getGameRules().set(GameRules.SPAWN_MOBS, false, null);
        settings.getGameRules().set(GameRules.ADVANCE_TIME, false, null);
        settings.getGameRules().set(GameRules.ADVANCE_WEATHER, false, null);
        settings.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, null);
        settings.getGameRules().set(GameRules.RESPAWN_RADIUS, 0, null);
    }

    private static void prepareClient(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer,
            boolean requireRenderedChunks) {
        context.runOnClient(client -> {
            client.options.pauseOnLostFocus = false;
            Axiom.configuration.internal.shownIntroduction = true;
        });
        context.setScreen(() -> null);
        context.waitForScreen(null);
        if (requireRenderedChunks) {
            singleplayer.getClientWorld().waitForChunksRender();
        } else {
            context.waitFor(client -> client.level != null && client.player != null
                    && client.level.getChunkSource().hasChunk(
                            client.player.chunkPosition().x,
                            client.player.chunkPosition().z));
        }
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
