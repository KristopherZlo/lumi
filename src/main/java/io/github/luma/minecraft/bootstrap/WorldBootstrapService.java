package io.github.luma.minecraft.bootstrap;

import io.github.luma.LumaMod;
import io.github.luma.domain.service.ProjectService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;

/**
 * Runs startup-only project metadata bootstrap away from the server start path.
 */
public final class WorldBootstrapService implements AutoCloseable {

    private static final AtomicInteger NEXT_BOOTSTRAP_THREAD_INDEX = new AtomicInteger(1);

    private final ProjectService projectService;
    private ExecutorService executor;
    private final AtomicReference<CompletableFuture<Void>> pendingBootstrap = new AtomicReference<>();
    private final WorldBootstrapDelay delay = new WorldBootstrapDelay();
    private MinecraftServer scheduledServer;

    public WorldBootstrapService() {
        this(new ProjectService(), Executors.newSingleThreadExecutor(WorldBootstrapService::bootstrapThread));
    }

    WorldBootstrapService(ProjectService projectService, ExecutorService executor) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void bootstrap(MinecraftServer server) {
        if (server == null) {
            return;
        }
        CompletableFuture<Void> previous = this.pendingBootstrap.get();
        if (previous != null && !previous.isDone()) {
            return;
        }

        this.scheduledServer = server;
        this.delay.reset();
    }

    public void tick(MinecraftServer server) {
        if (server == null || this.scheduledServer != server) {
            return;
        }
        if (server.getPlayerList().getPlayerCount() <= 0) {
            return;
        }
        if (!this.delay.tick(this.chunkLoadingActive(server))) {
            return;
        }

        this.scheduledServer = null;
        this.startBootstrap(server);
    }

    @Override
    public synchronized void close() {
        this.scheduledServer = null;
        this.delay.reset();
        CompletableFuture<Void> pending = this.pendingBootstrap.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
        this.executor.shutdownNow();
    }

    private void startBootstrap(MinecraftServer server) {
        CompletableFuture<Void> previous = this.pendingBootstrap.get();
        if (previous != null && !previous.isDone()) {
            return;
        }

        CompletableFuture<Void> next = CompletableFuture.runAsync(() -> this.bootstrapNow(server), this.executor());
        this.pendingBootstrap.set(next);
    }

    private void bootstrapNow(MinecraftServer server) {
        try {
            this.projectService.bootstrapWorld(server);
            LumaMod.LOGGER.info("Completed async world origin metadata bootstrap");
        } catch (Throwable throwable) {
            LumaMod.LOGGER.warn("Failed to bootstrap world origin metadata", throwable);
        }
    }

    private boolean chunkLoadingActive(MinecraftServer server) {
        for (var level : server.getAllLevels()) {
            if (level.getChunkSource().getPendingTasksCount() > 0) {
                return true;
            }
        }
        return false;
    }

    private synchronized ExecutorService executor() {
        if (this.executor.isShutdown() || this.executor.isTerminated()) {
            this.executor = Executors.newSingleThreadExecutor(WorldBootstrapService::bootstrapThread);
        }
        return this.executor;
    }

    private static Thread bootstrapThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "lumi-world-bootstrap-" + NEXT_BOOTSTRAP_THREAD_INDEX.getAndIncrement());
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    }
}
