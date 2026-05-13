package io.github.luma.gametest;

import io.github.luma.minecraft.testing.SingleplayerTestingService;
import java.util.List;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.server.level.ServerPlayer;

@SuppressWarnings("UnstableApiUsage")
public final class LumiClientGameTests implements FabricClientGameTest {

    private static final int SINGLEPLAYER_RUNTIME_TIMEOUT_TICKS = 20 * 240;

    @Override
    public void runTest(ClientGameTestContext context) {
        if (this.modeIs("backup-stress", "backup")) {
            this.runBackupStress(context);
            return;
        }
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            ClientGameTestSingleplayerSupport.prepare(singleplayer);
            this.startSingleplayerRuntimeSuite(singleplayer);
            this.waitForSingleplayerRuntimeSuite(context, singleplayer);
            context.takeScreenshot("lumi-client-smoke");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi client gametest failed", exception);
        }
    }

    private void runBackupStress(ClientGameTestContext context) {
        try {
            new LumiBackupStressClientScenario().run(context);
            context.takeScreenshot("lumi-backup-stress");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi backup stress client gametest failed", exception);
        }
    }

    private void startSingleplayerRuntimeSuite(TestSingleplayerContext singleplayer) throws Exception {
        singleplayer.getServer().runOnServer(server -> {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) {
                throw new IllegalStateException("No singleplayer test player is available");
            }
            ServerPlayer player = players.get(0);
            if (this.modeIs("full", "singleplayer")) {
                SingleplayerTestingService.getInstance().start(server, server.overworld(), player);
            } else if (this.modeIs("player-flow", "player", "natural")) {
                SingleplayerTestingService.getInstance().startPlayerFlow(server, server.overworld(), player);
            } else if (this.modeIs("structure-fixtures", "structures")) {
                SingleplayerTestingService.getInstance().startStructureFixtures(server, server.overworld(), player);
            } else if (this.modeIs("crash-safety", "crash")) {
                SingleplayerTestingService.getInstance().startCrashSafety(server, server.overworld(), player);
            } else if (this.modeIs("external-tools", "tools")) {
                SingleplayerTestingService.getInstance().startExternalTools(server, server.overworld(), player);
            } else {
                SingleplayerTestingService.getInstance().startSmoke(server, server.overworld(), player);
            }
        });
    }

    private boolean modeIs(String... acceptedModes) {
        String mode = System.getProperty(
                "lumi.singleplayerTest.mode",
                System.getenv().getOrDefault("LUMI_SINGLEPLAYER_TEST_MODE", "")
        );
        for (String acceptedMode : acceptedModes) {
            if (acceptedMode.equalsIgnoreCase(mode)) {
                return true;
            }
        }
        return false;
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
