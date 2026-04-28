package io.github.luma;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.StartupProfiler;
import io.github.luma.integration.OptionalIntegrationBootstrap;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.command.LumaCommands;
import io.github.luma.minecraft.bootstrap.WorldBootstrapService;
import io.github.luma.minecraft.testing.SingleplayerTestingService;
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
    private final WorldBootstrapService worldBootstrapService = new WorldBootstrapService();
    private final OptionalIntegrationBootstrap optionalIntegrations = new OptionalIntegrationBootstrap();

    @Override
    public void onInitialize() {
        long startedAt = StartupProfiler.start();
        long integrationsStartedAt = StartupProfiler.start();
        this.optionalIntegrations.initialize();
        StartupProfiler.logElapsed("main.optional-integrations", integrationsStartedAt);
        long commandsStartedAt = StartupProfiler.start();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> this.commands.register(dispatcher));
        StartupProfiler.logElapsed("main.command-registration-callback", commandsStartedAt);
        long eventsStartedAt = StartupProfiler.start();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WorldOperationManager.getInstance().tick(server);
            SingleplayerTestingService.getInstance().tick(server);
            HistoryCaptureManager.getInstance().flushIdleSessions(server);
            this.worldBootstrapService.tick(server);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(this.worldBootstrapService::bootstrap);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            this.worldBootstrapService.close();
            HistoryCaptureManager.getInstance().flushAll(server);
            WorldOperationManager.getInstance().shutdown();
        });
        StartupProfiler.logElapsed("main.fabric-events", eventsStartedAt);
        LOGGER.info("{} bootstrap initialized", MOD_NAME);
        if (LumaDebugLog.globalEnabled()) {
            LOGGER.info("{} global debug logging is enabled via -Dlumi.debug=true", MOD_NAME);
        }
        StartupProfiler.logElapsed("main.onInitialize", startedAt);
    }
}
