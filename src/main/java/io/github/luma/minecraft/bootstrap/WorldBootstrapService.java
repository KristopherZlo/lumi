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

    private final ProjectService projectService;
    private ExecutorService executor;
    private final AtomicReference<CompletableFuture<Void>> pendingBootstrap = new AtomicReference<>();

    public WorldBootstrapService() {
        this(new ProjectService(), Executors.newSingleThreadExecutor(new BootstrapThreadFactory()));
    }

    WorldBootstrapService(ProjectService projectService, ExecutorService executor) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void bootstrap(MinecraftServer server) {
        CompletableFuture<Void> previous = this.pendingBootstrap.get();
        if (previous != null && !previous.isDone()) {
            return;
        }

        CompletableFuture<Void> next = CompletableFuture.runAsync(() -> this.bootstrapNow(server), this.executor());
        this.pendingBootstrap.set(next);
    }

    @Override
    public synchronized void close() {
        CompletableFuture<Void> pending = this.pendingBootstrap.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
        this.executor.shutdownNow();
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
