package io.github.luma;

import io.github.luma.debug.LumaDebugLog;
import io.github.luma.debug.LumaDiagnosticsLog;
import io.github.luma.debug.LumaLoadLog;
import io.github.luma.debug.StartupProfiler;
import io.github.luma.debug.TesterDiagnosticsMode;
import io.github.luma.integration.OptionalIntegrationBootstrap;
import io.github.luma.minecraft.capture.EntityMutationTracker;
import io.github.luma.minecraft.capture.HistoryCaptureManager;
import io.github.luma.minecraft.command.LumaCommands;
import io.github.luma.minecraft.bootstrap.WorldBootstrapService;
import io.github.luma.minecraft.world.WorldOperationBossBarManager;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.network.WorkZoneServerNetworking;
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
    private final WorldOperationBossBarManager operationBossBars = new WorldOperationBossBarManager();
    private final WorkZoneServerNetworking workZoneNetworking = new WorkZoneServerNetworking();

    @Override
    public void onInitialize() {
        boolean testerDiagnosticsEnabled = TesterDiagnosticsMode.applyDefaults();
        long startedAt = StartupProfiler.start();
        long integrationsStartedAt = StartupProfiler.start();
        this.optionalIntegrations.initialize();
        StartupProfiler.logElapsed("main.optional-integrations", integrationsStartedAt);
        long commandsStartedAt = StartupProfiler.start();
        this.workZoneNetworking.register();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> this.commands.register(dispatcher));
        StartupProfiler.logElapsed("main.command-registration-callback", commandsStartedAt);
        long eventsStartedAt = StartupProfiler.start();
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (!LumaLoadLog.enabled()) {
                WorldOperationManager.getInstance().tick(server);
                this.operationBossBars.tick(server);
                EntityMutationTracker.tick(server);
                HistoryCaptureManager.getInstance().flushIdleSessions(server);
                this.worldBootstrapService.tick(server);
                return;
            }
            try (var ignored = LumaLoadLog.measure("server-tick", "LumaMod.endServerTick")) {
                try (var worldOperationTick = LumaLoadLog.measure("server-tick", "WorldOperationManager.tick")) {
                    WorldOperationManager.getInstance().tick(server);
                }
                try (var bossBarTick = LumaLoadLog.measure("server-tick", "WorldOperationBossBarManager.tick")) {
                    this.operationBossBars.tick(server);
                }
                try (var entityMutationTick = LumaLoadLog.measure("server-tick", "EntityMutationTracker.tick")) {
                    EntityMutationTracker.tick(server);
                }
                try (var idleFlushTick = LumaLoadLog.measure("server-tick", "HistoryCaptureManager.flushIdleSessions")) {
                    HistoryCaptureManager.getInstance().flushIdleSessions(server);
                }
                try (var bootstrapTick = LumaLoadLog.measure("server-tick", "WorldBootstrapService.tick")) {
                    this.worldBootstrapService.tick(server);
                }
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(this.worldBootstrapService::bootstrap);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            try (var ignored = LumaLoadLog.measure("lifecycle", "server-stopping")) {
                this.worldBootstrapService.close();
                this.operationBossBars.clear();
                EntityMutationTracker.drainPendingSpawns(server);
                HistoryCaptureManager.getInstance().flushAll(server);
                HistoryCaptureManager.getInstance().invalidateProjectCache(server);
                WorldOperationManager.getInstance().shutdown(server);
            } finally {
                LumaDiagnosticsLog.close();
                LumaLoadLog.close();
            }
        });
        StartupProfiler.logElapsed("main.fabric-events", eventsStartedAt);
        LOGGER.info("{} bootstrap initialized", MOD_NAME);
        if (LumaDebugLog.globalEnabled()) {
            LOGGER.info("{} global debug logging is enabled via -Dlumi.debug=true", MOD_NAME);
        }
        if (testerDiagnosticsEnabled) {
            LOGGER.info("{} tester diagnostics are enabled", MOD_NAME);
        }
        if (LumaLoadLog.enabled()) {
            LOGGER.info("{} load logging is enabled at {}", MOD_NAME, LumaLoadLog.configuredPath());
            LumaLoadLog.event("lifecycle", "mod-initialized", "path=" + LumaLoadLog.configuredPath());
        }
        if (LumaDiagnosticsLog.lightEnabled()) {
            LOGGER.info("{} lighting diagnostics are enabled at {}", MOD_NAME, LumaDiagnosticsLog.lightPath());
            LumaDiagnosticsLog.lightEvent("mod-initialized", "path=" + LumaDiagnosticsLog.lightPath());
        }
        if (LumaDiagnosticsLog.blockApplyEnabled()) {
            LOGGER.info("{} block apply diagnostics are enabled at {}", MOD_NAME, LumaDiagnosticsLog.blockApplyPath());
            LumaDiagnosticsLog.blockApplyEvent("mod-initialized", "path=" + LumaDiagnosticsLog.blockApplyPath());
        }
        if (LumaDiagnosticsLog.partialRestoreEnabled()) {
            LOGGER.info("{} partial restore diagnostics are enabled at {}", MOD_NAME, LumaDiagnosticsLog.partialRestorePath());
            LumaDiagnosticsLog.partialRestoreEvent("mod-initialized", "path=" + LumaDiagnosticsLog.partialRestorePath());
        }
        StartupProfiler.logElapsed("main.onInitialize", startedAt);
    }
}
