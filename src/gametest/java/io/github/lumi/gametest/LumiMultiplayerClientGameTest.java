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
            settings.setProperty("server-port", "0");
            settings.setProperty("view-distance", "4");
            settings.setProperty("gamemode", "creative");
            try (var report = LumiBehaviorReport.create(FabricLoader.getInstance().getGameDir(),
                    "multiplayer");
                    var server = context.worldBuilder()
                            .adjustSettings(LumiClientBehaviorWorld::configureWorld).createServer(settings);
                    var connection = server.connect()) {
                Properties ready = new Properties();
                ready.setProperty("address", "localhost:" + server.computeOnServer(
                        minecraft -> minecraft.getPort()));
                mailbox.publish("server", ready);
                context.waitFor(client -> server.computeOnServer(
                        minecraft -> minecraft.getPlayerList().getPlayerCount()) == 2, 12000);
                var ui = new LumiUiTestDriver(context);
                ui.completeOnboardingIfShown();
                ui.awaitHistory();
                var operations = new LumiBehaviorOperations(context, server, report);
                var saved = operations.save("multiplayer-shared");
                mailbox.request(context, "head", saved.hex());
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
