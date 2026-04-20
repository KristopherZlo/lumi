package io.github.luma;

import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.command.LumaCommands;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LumaMod implements ModInitializer {

    public static final String MOD_ID = "luma";
    public static final String MOD_NAME = "Luma";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    private final LumaCommands commands = new LumaCommands();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> this.commands.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(server -> HistoryCaptureManager.getInstance().flushIdleSessions(server));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> HistoryCaptureManager.getInstance().flushAll(server));
        LOGGER.info("{} bootstrap initialized", MOD_NAME);
    }
}
