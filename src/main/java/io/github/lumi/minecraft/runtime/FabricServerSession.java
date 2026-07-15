package io.github.lumi.minecraft.runtime;

import io.github.lumi.storage.repository.DimensionRepositoryLayout;
import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

/** Owns all dimension runtimes and bounded background workers for one server. */
final class FabricServerSession implements AutoCloseable {
    private static final int BACKGROUND_THREADS = 2;
    private static final int BACKGROUND_QUEUE = 256;
    private final MinecraftServer server;
    private final DimensionRepositoryLayout layout;
    private final ExecutorService background;
    private final LoadedDimensionRegistry<ServerLevel, FabricDimensionRuntime> dimensions =
            new LoadedDimensionRegistry<>();

    FabricServerSession(MinecraftServer server) {
        this.server = server;
        layout = new DimensionRepositoryLayout(server.getWorldPath(LevelResource.ROOT));
        AtomicInteger threadNumber = new AtomicInteger();
        background = new ThreadPoolExecutor(
                BACKGROUND_THREADS, BACKGROUND_THREADS, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(BACKGROUND_QUEUE), runnable -> {
                    Thread thread = new Thread(runnable,
                            "Lumi-V2-Background-" + threadNumber.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    void load(ServerLevel level) throws IOException {
        requireServer(level);
        dimensions.load(level, FabricDimensionRuntime.open(level, layout, background));
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

    @Override
    public void close() throws Exception {
        dimensions.close();
        background.shutdown();
        if (!background.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Lumi background durability work did not finish in 30 seconds");
        }
    }

    private void requireServer(ServerLevel level) {
        if (level.getServer() != server) {
            throw new IllegalArgumentException("Dimension belongs to another Minecraft server");
        }
    }
}
