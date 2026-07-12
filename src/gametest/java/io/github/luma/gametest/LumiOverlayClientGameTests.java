package io.github.luma.gametest;

import io.github.luma.ui.overlay.LumiOverlayClientSmoke;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

@SuppressWarnings("UnstableApiUsage")
public final class LumiOverlayClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
                .adjustSettings(settings -> settings.setAllowCommands(true))
                .create()) {
            ClientGameTestSingleplayerSupport.prepare(singleplayer);
            new LumiOverlayClientSmoke().run(context, singleplayer);
            context.takeScreenshot("lumi-overlay-client-smoke");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi overlay client gametest failed", exception);
        }
    }
}
