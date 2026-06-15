package io.github.luma.gametest;

import io.github.luma.minecraft.testing.SingleplayerTestingService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/**
 * Wires GameTest-only runtime harness services into the integrated server loop.
 */
public final class LumiGameTestRuntimeHooks implements ModInitializer {

    @Override
    public void onInitialize() {
        ServerTickEvents.END_SERVER_TICK.register(server ->
                SingleplayerTestingService.getInstance().tick(server));
    }
}
