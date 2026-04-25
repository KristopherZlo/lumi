package io.github.luma;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.command.LumaCommands;
import io.github.luma.domain.service.ProjectService;
import io.github.luma.minecraft.world.WorldOperationManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric bootstrap entry point for the mod.
 *
 * <p>The initializer keeps wiring intentionally small: register commands,
 * advance world operations on each server tick, flush capture sessions on idle
 * ticks, and persist active state on server shutdown.
 */
public final class LumaMod implements ModInitializer {

    public static final String MOD_ID = "lumi";
    public static final String MOD_NAME = "Lumi";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);
    private final LumaCommands commands = new LumaCommands();
    private final ProjectService projectService = new ProjectService();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> this.commands.register(dispatcher));
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WorldOperationManager.getInstance().tick(server);
            HistoryCaptureManager.getInstance().flushIdleSessions(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            try {
                this.projectService.bootstrapWorld(server);
            } catch (Exception exception) {
                LOGGER.warn("Failed to bootstrap world origin metadata", exception);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            HistoryCaptureManager.getInstance().flushAll(server);
            WorldOperationManager.getInstance().shutdown();
        });
        LOGGER.info("{} bootstrap initialized", MOD_NAME);
        if (LumaDebugLog.globalEnabled()) {
            LOGGER.info("{} global debug logging is enabled via -Dlumi.debug=true", MOD_NAME);
        }
    }
}
