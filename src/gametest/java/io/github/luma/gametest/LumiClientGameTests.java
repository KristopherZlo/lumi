package io.github.luma.gametest;

import io.github.luma.minecraft.testing.SingleplayerTestingService;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;

@SuppressWarnings("UnstableApiUsage")
public final class LumiClientGameTests implements FabricClientGameTest {

    private static final int SINGLEPLAYER_RUNTIME_TIMEOUT_TICKS = 20 * 120;

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            this.startSingleplayerRuntimeSuite(singleplayer);
            this.waitForSingleplayerRuntimeSuite(context, singleplayer);
            context.takeScreenshot("lumi-client-smoke");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi client gametest failed", exception);
        }
    }

    private void startSingleplayerRuntimeSuite(TestSingleplayerContext singleplayer) throws Exception {
        singleplayer.getServer().runOnServer(server -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                throw new IllegalStateException("No singleplayer test player is available");
            }
            ServerPlayer player = players.get(0);
            SingleplayerTestingService.getInstance().start(server, server.overworld(), player);
        });
    }

    private void waitForSingleplayerRuntimeSuite(
            ClientGameTestContext context,
            TestSingleplayerContext singleplayer
    ) throws Exception {
        for (int tick = 0; tick < SINGLEPLAYER_RUNTIME_TIMEOUT_TICKS; tick++) {
            boolean active = singleplayer.getServer().computeOnServer(server ->
                    SingleplayerTestingService.getInstance().hasActiveRun(server));
            if (!active) {
                this.assertSingleplayerRuntimeSuitePassed(singleplayer);
                return;
            }
            context.waitTick();
        }
        throw new AssertionError("Timed out waiting for Lumi singleplayer runtime suite");
    }

    private void assertSingleplayerRuntimeSuitePassed(TestSingleplayerContext singleplayer) throws Exception {
        boolean passed = singleplayer.getServer().computeOnServer(server ->
                SingleplayerTestingService.getInstance().lastRunPassed(server));
        if (!passed) {
            throw new AssertionError("Lumi singleplayer runtime suite completed with failures");
        }
    }
}
