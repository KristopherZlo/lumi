package io.github.luma.minecraft.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ServerThreadExecutorTest {

    @Test
    void runsImmediatelyWhenAlreadyOnServerThread() throws IOException {
        ServerThreadExecutor executor = new ServerThreadExecutor();
        CountingScheduler scheduler = new CountingScheduler(true);

        String result = executor.call(scheduler, () -> "done");

        assertEquals("done", result);
        assertEquals(0, scheduler.submissions());
    }

    @Test
    void submitsWorkWhenCallerIsNotServerThread() throws IOException {
        ServerThreadExecutor executor = new ServerThreadExecutor();
        ExecutorService serverThread = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "lumi-test-server"));
        AtomicReference<String> workerThread = new AtomicReference<>();
        CountingScheduler scheduler = new CountingScheduler(false, serverThread);

        try {
            String result = executor.call(scheduler, () -> {
                workerThread.set(Thread.currentThread().getName());
                return "done";
            });

            assertEquals("done", result);
            assertEquals("lumi-test-server", workerThread.get());
            assertEquals(1, scheduler.submissions());
        } finally {
            serverThread.shutdownNow();
        }
    }

    @Test
    void unwrapsIoFailuresFromSubmittedWork() {
        ServerThreadExecutor executor = new ServerThreadExecutor();
        CountingScheduler scheduler = new CountingScheduler(false);
        IOException failure = new IOException("dirty chunk failure");

        IOException thrown = assertThrows(IOException.class, () -> executor.call(scheduler, () -> {
            throw failure;
        }));

        assertSame(failure, thrown);
    }

    @Test
    void preservesRuntimeFailuresFromSubmittedWork() {
        ServerThreadExecutor executor = new ServerThreadExecutor();
        CountingScheduler scheduler = new CountingScheduler(false);
        IllegalStateException failure = new IllegalStateException("busy");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> executor.call(scheduler, () -> {
            throw failure;
        }));

        assertSame(failure, thrown);
    }

    private static final class CountingScheduler implements ServerThreadExecutor.ServerScheduler {

        private final boolean sameThread;
        private final ExecutorService executor;
        private final AtomicInteger submissions = new AtomicInteger();

        private CountingScheduler(boolean sameThread) {
            this(sameThread, null);
        }

        private CountingScheduler(boolean sameThread, ExecutorService executor) {
            this.sameThread = sameThread;
            this.executor = executor;
        }

        @Override
        public boolean isSameThread() {
            return this.sameThread;
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> supplier) {
            this.submissions.incrementAndGet();
            if (this.executor == null) {
                try {
                    return CompletableFuture.completedFuture(supplier.get());
                } catch (Throwable throwable) {
                    return CompletableFuture.failedFuture(throwable);
                }
            }
            return CompletableFuture.supplyAsync(supplier, this.executor);
        }

        private int submissions() {
            return this.submissions.get();
        }
    }
}
