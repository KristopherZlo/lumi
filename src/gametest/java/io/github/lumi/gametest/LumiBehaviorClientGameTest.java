package io.github.lumi.gametest;

import io.github.lumi.domain.model.BlockBox;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.gamerules.GameRules;

/** End-to-end singleplayer builder workflow with real production mods loaded. */
@SuppressWarnings("UnstableApiUsage")
public final class LumiBehaviorClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try (LumiBehaviorReport report = LumiBehaviorReport.create(
                FabricLoader.getInstance().getGameDir())) {
            long started = System.nanoTime();
            try (TestSingleplayerContext singleplayer = context.worldBuilder()
                    .setUseConsistentSettings(false)
                    .adjustSettings(settings -> configureWorld(settings))
                    .create()) {
                context.setScreen(() -> null);
                context.waitForScreen(null);
                singleplayer.getClientWorld().waitForChunksRender();
                BlockPos origin = singleplayer.getServer().computeOnServer(server ->
                        server.getPlayerList().getPlayers().getFirst().blockPosition());
                singleplayer.getServer().runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    LumiWorldSnapshot.capture(
                            player.level(), initialAreas(origin), report, "initial");
                });
                report.event("stage", "world_create", "succeeded", 0,
                        elapsedMillis(started), "seed=710 mode=creative");
                context.takeScreenshot("lumi-behavior-world-created");
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
    }

    private static List<BlockBox> initialAreas(BlockPos origin) {
        return List.of(
                new BlockBox(
                        origin.getX() - 24, origin.getY() - 16, origin.getZ() - 24,
                        origin.getX() + 24, origin.getY() + 16, origin.getZ() + 24),
                new BlockBox(
                        origin.getX() - 40, origin.getY() + 60, origin.getZ() - 40,
                        origin.getX() + 40, origin.getY() + 140, origin.getZ() + 40));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }
}
