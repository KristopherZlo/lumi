package io.github.luma.minecraft.capture;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;

/**
 * Marshals capture state transitions onto the Minecraft server thread.
 */
final class ServerThreadExecutor {

    <T> T call(MinecraftServer server, IoTask<T> task) throws IOException {
        if (server == null) {
            return this.call(ImmediateScheduler.INSTANCE, task);
        }
        return this.call(new MinecraftServerScheduler(server), task);
    }

    void run(MinecraftServer server, IoRunnable task) throws IOException {
        this.call(server, () -> {
            task.run();
            return null;
        });
    }

    <T> T call(ServerScheduler scheduler, IoTask<T> task) throws IOException {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(task, "task");
        if (scheduler.isSameThread()) {
            return task.run();
        }
        return this.join(scheduler.submit(() -> this.runUnchecked(task)));
    }

    private <T> T runUnchecked(IoTask<T> task) {
        try {
            return task.run();
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private <T> T join(CompletableFuture<T> future) throws IOException {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = this.unwrap(exception);
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("Server thread task failed", cause);
        }
    }

    private Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @FunctionalInterface
    interface IoTask<T> {
        T run() throws IOException;
    }

    @FunctionalInterface
    interface IoRunnable {
        void run() throws IOException;
    }

    interface ServerScheduler {
        boolean isSameThread();

        <T> CompletableFuture<T> submit(Supplier<T> supplier);
    }

    private record MinecraftServerScheduler(MinecraftServer server) implements ServerScheduler {

        @Override
        public boolean isSameThread() {
            return this.server.isSameThread();
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
            return this.server.submit(supplier);
        }
    }

    private enum ImmediateScheduler implements ServerScheduler {
        INSTANCE;

        @Override
        public boolean isSameThread() {
            return true;
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
            return CompletableFuture.completedFuture(supplier.get());
        }
    }
}
