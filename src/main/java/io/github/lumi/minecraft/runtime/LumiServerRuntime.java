package io.github.lumi.minecraft.runtime;

import io.github.lumi.LumiMod;
import java.io.IOException;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/** Binds the same dimension runtime lifecycle to integrated and dedicated servers. */
public final class LumiServerRuntime {
    private FabricServerSession session;

    public void registerEvents() {
        ServerLifecycleEvents.SERVER_STARTING.register(this::starting);
        ServerWorldEvents.LOAD.register(this::load);
        ServerTickEvents.START_WORLD_TICK.register(this::tick);
        ServerWorldEvents.UNLOAD.register(this::unload);
        ServerLifecycleEvents.SERVER_STOPPED.register(this::stopped);
    }

    public synchronized Optional<FabricDimensionRuntime> find(ServerLevel level) {
        return session == null ? Optional.empty() : session.find(level);
    }

    private synchronized void starting(MinecraftServer server) {
        if (session != null) {
            throw new IllegalStateException("A Lumi server session is already active");
        }
        session = new FabricServerSession(server);
    }

    private void load(MinecraftServer server, ServerLevel level) {
        try {
            requireSession().load(level);
        } catch (IOException failed) {
            throw new IllegalStateException("Cannot open Lumi dimension repository", failed);
        }
    }

    private void tick(ServerLevel level) {
        try {
            requireSession().tick(level);
        } catch (IOException failed) {
            LumiMod.LOGGER.error("Lumi dimension operation failed; its freeze and journal are retained", failed);
        }
    }

    private void unload(MinecraftServer server, ServerLevel level) {
        try {
            requireSession().unload(level);
        } catch (Exception failed) {
            LumiMod.LOGGER.error("Failed to close Lumi dimension runtime", failed);
        }
    }

    private synchronized void stopped(MinecraftServer server) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception failed) {
            LumiMod.LOGGER.error("Failed to finish Lumi server runtime", failed);
        } finally {
            session = null;
        }
    }

    private synchronized FabricServerSession requireSession() {
        if (session == null) {
            throw new IllegalStateException("Lumi server session is not active");
        }
        return session;
    }
}
