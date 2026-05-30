package io.github.luma.minecraft.testing;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

@SuppressWarnings("UnstableApiUsage")
public final class HistoryJourneyClientGameTests implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            HistoryJourneySingleplayerSupport.prepare(singleplayer);
            new HistoryJourneyScenario(context).run(singleplayer);
            context.takeScreenshot("lumi-history-journey");
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Exception exception) {
            throw new RuntimeException("Lumi history journey gametest failed", exception);
        }
    }
}
