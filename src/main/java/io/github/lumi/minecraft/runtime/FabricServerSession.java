package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.service.LumiPermissionService;
import io.github.lumi.domain.service.PermissionDecision;
import io.github.lumi.domain.service.PermissionSubject;
import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import io.github.lumi.storage.repository.SurvivalOptInRepository;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;

/** Owns all dimension runtimes and bounded background workers for one server. */
final class FabricServerSession implements AutoCloseable {
    private static final int BACKGROUND_THREADS = 2;
    private static final int BACKGROUND_QUEUE = 256;
    private final MinecraftServer server;
    private final DimensionRepositoryLayout layout;
    private final LumiPermissionService permissions;
    private final ExecutorService operationBackground;
    private final ExecutorService durabilityBackground;
    private final LoadedDimensionRegistry<ServerLevel, FabricDimensionRuntime> dimensions =
            new LoadedDimensionRegistry<>();

    FabricServerSession(MinecraftServer server) {
        this.server = server;
        var worldRoot = server.getWorldPath(LevelResource.ROOT);
        layout = new DimensionRepositoryLayout(worldRoot);
        permissions = new LumiPermissionService(new SurvivalOptInRepository(worldRoot));
        operationBackground = newBackgroundExecutor("Operation");
        durabilityBackground = newBackgroundExecutor("Durability");
    }

    private static ExecutorService newBackgroundExecutor(String role) {
        AtomicInteger threadNumber = new AtomicInteger();
        return new ThreadPoolExecutor(
                BACKGROUND_THREADS, BACKGROUND_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BACKGROUND_QUEUE), runnable -> {
                    Thread thread = new Thread(runnable,
                            "Lumi-V2-" + role + "-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    void load(ServerLevel level) throws IOException {
        requireServer(level);
        dimensions.load(level, FabricDimensionRuntime.open(
                level, layout, operationBackground, durabilityBackground));
    }

    void tick(MinecraftServer tickingServer) throws IOException {
        if (tickingServer != server) {
            throw new IllegalArgumentException("Tick belongs to another Minecraft server");
        }
        IOException failure = null;
        for (FabricDimensionRuntime runtime : dimensions.loadedValues()) {
            try {
                runtime.tick();
            } catch (IOException dimensionFailure) {
                if (failure == null) {
                    failure = dimensionFailure;
                } else {
                    failure.addSuppressed(dimensionFailure);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    void unload(ServerLevel level) throws Exception {
        requireServer(level);
        dimensions.unload(level);
    }

    Optional<FabricDimensionRuntime> find(ServerLevel level) {
        return dimensions.find(level);
    }

    PermissionDecision permission(ServerPlayer player) throws IOException {
        return permissions.evaluate(subject(player));
    }

    void setSurvivalEnabled(ServerPlayer player, boolean enabled) throws IOException {
        permissions.setSurvivalEnabled(subject(player), enabled);
    }

    boolean mayConfigure(ServerPlayer player) {
        return server.getPlayerList().isOp(player.nameAndId());
    }

    @Override
    public void close() throws Exception {
        dimensions.close();
        operationBackground.shutdown();
        durabilityBackground.shutdown();
        boolean operationsFinished =
                operationBackground.awaitTermination(30, TimeUnit.SECONDS);
        boolean durabilityFinished =
                durabilityBackground.awaitTermination(30, TimeUnit.SECONDS);
        if (!operationsFinished || !durabilityFinished) {
            throw new IllegalStateException(
                    "Lumi background work did not finish in 30 seconds"
                            + " (operations=" + operationsFinished
                            + ", durability=" + durabilityFinished + ")");
        }
    }

    private void requireServer(ServerLevel level) {
        if (level.getServer() != server) {
            throw new IllegalArgumentException("Dimension belongs to another Minecraft server");
        }
    }

    private PermissionSubject subject(ServerPlayer player) {
        return new PermissionSubject(
                player.getUUID(), mayConfigure(player),
                player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL);
    }
}
