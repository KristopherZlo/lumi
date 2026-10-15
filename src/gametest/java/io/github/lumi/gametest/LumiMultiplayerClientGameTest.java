package io.github.lumi.gametest;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.util.Properties;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;

/** Two-process client gate against Fabric's real TCP dedicated server. */
public final class LumiMultiplayerClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        if (!LumiClientTestSuite.includes(LumiClientTestSuite.MULTIPLAYER)
                || System.getProperty("lumi.multiplayer.mailbox") == null) return;
        try (var viewport = LumiUiScaleTestScope.readableViewport()) {
            var mailbox = new LumiMultiplayerMailbox();
            if (System.getProperty("lumi.multiplayer.role", "owner").equals("peer")) {
                new LumiMultiplayerPeer(context, mailbox).run();
                return;
            }
            Properties settings = new Properties();
            settings.setProperty("online-mode", "false");
            settings.setProperty("view-distance", "4");
            settings.setProperty("gamemode", "creative");
            settings.setProperty("max-players", "2");
            try (var report = LumiBehaviorReport.create(FabricLoader.getInstance().getGameDir(),
                    "multiplayer");
                    var server = context.worldBuilder()
                            .adjustSettings(LumiClientBehaviorWorld::configureWorld).createServer(settings);
                    var connection = server.connect()) {
                Properties ready = new Properties();
                ready.setProperty("address", "localhost:" + server.computeOnServer(
                        minecraft -> minecraft.getPort()));
                mailbox.publish("server", ready);
                int ticks = 0;
                while (server.computeOnServer(minecraft ->
                        minecraft.getPlayerList().getPlayerCount()) != 2) {
                    if (++ticks > 12000) throw new AssertionError("Second client did not join");
                    context.waitTick();
                }
                server.runOnServer(minecraft -> minecraft.getPlayerList().getPlayers().forEach(
                        player -> minecraft.getPlayerList().op(new net.minecraft.server.players.NameAndId(
                                player.getUUID(), player.getName().getString()))));
                context.waitFor(client -> client.player != null && client.player.canUseGameMasterBlocks());
                context.runOnClient(client -> {
                    client.options.pauseOnLostFocus = false;
                    if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("axiom")) {
                        com.moulberry.axiom.Axiom.configuration.internal.shownIntroduction = true;
                    }
                });
                context.setScreen(() -> null);
                var ui = new LumiUiTestDriver(context);
                ui.completeOnboardingIfShown();
                ui.awaitHistory();
                new LumiDenseSectionFixture(context, server, report).fill("multiplayer-fixture",
                        new io.github.lumi.domain.model.BlockBox(0, 0, 0, 15, 0, 15), 0);
                var operations = new LumiBehaviorOperations(context, server, report);
                var saved = operations.save("multiplayer-shared");
                mailbox.request(context, "head", saved.hex());
                new LumiMultiplayerQueueScenario(context, server, report, mailbox).run();
                new LumiMultiplayerApplyScenario(context, server, report, mailbox).run();
                server.runOnServer(minecraft -> {
                    var runtime = LumiMod.serverRuntime().find(minecraft.overworld()).orElseThrow();
                    if (runtime.operations().hasActiveOperation() || runtime.freeze().isFrozen()) {
                        throw new AssertionError("Multiplayer Save retained operation ownership");
                    }
                });
                mailbox.request(context, "stop", "");
                report.event("gate", "multiplayer_shared_history", "succeeded", 0, 0,
                        "clients=2;transport=TCP;server=dedicated-in-owner-jvm");
            }
        } catch (IOException failed) {
            throw new IllegalStateException("Multiplayer harness failed", failed);
        }
    }
}
