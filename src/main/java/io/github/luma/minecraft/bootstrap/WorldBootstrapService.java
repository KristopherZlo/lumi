package io.github.luma.minecraft.bootstrap;

import io.github.luma.LumaMod;
import io.github.luma.domain.service.ProjectService;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.MinecraftServer;

/**
 * Runs startup-only project metadata bootstrap away from the server start path.
 */
public final class WorldBootstrapService implements AutoCloseable {

    private static final int PLAYER_JOIN_BOOTSTRAP_DELAY_TICKS = 20 * 10;

    private final ProjectService projectService;
    private ExecutorService executor;
    private final AtomicReference<CompletableFuture<Void>> pendingBootstrap = new AtomicReference<>();
    private MinecraftServer scheduledServer;
    private int ticksUntilBootstrap = -1;

    public WorldBootstrapService() {
        this(new ProjectService(), Executors.newSingleThreadExecutor(new BootstrapThreadFactory()));
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
        this.ticksUntilBootstrap = PLAYER_JOIN_BOOTSTRAP_DELAY_TICKS;
    }

    public void tick(MinecraftServer server) {
        if (server == null || this.scheduledServer != server || this.ticksUntilBootstrap < 0) {
            return;
        }
        if (server.getPlayerList().getPlayerCount() <= 0) {
            return;
        }
        this.ticksUntilBootstrap -= 1;
        if (this.ticksUntilBootstrap > 0) {
            return;
        }

        this.scheduledServer = null;
        this.ticksUntilBootstrap = -1;
        this.startBootstrap(server);
    }

    @Override
    public synchronized void close() {
        this.scheduledServer = null;
        this.ticksUntilBootstrap = -1;
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

    private synchronized ExecutorService executor() {
        if (this.executor.isShutdown() || this.executor.isTerminated()) {
            this.executor = Executors.newSingleThreadExecutor(new BootstrapThreadFactory());
        }
        return this.executor;
    }

    private static final class BootstrapThreadFactory implements ThreadFactory {

        private int index;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lumi-world-bootstrap-" + (++this.index));
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        }
    }
}
