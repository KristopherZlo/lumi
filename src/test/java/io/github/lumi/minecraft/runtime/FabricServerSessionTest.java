package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FabricServerSessionTest {
    @Test
    void interruptsOperationsWithoutWaitingAndDrainsDurabilityOnce() {
        RecordingExecutor operations = new RecordingExecutor(true);
        RecordingExecutor durability = new RecordingExecutor(true);

        assertTrue(FabricServerSession.stopBackgroundWorkers(
                operations, durability, 5, TimeUnit.SECONDS));

        assertTrue(operations.shutdownNow);
        assertFalse(operations.awaited);
        assertTrue(durability.shutdown);
        assertTrue(durability.awaited);
        assertFalse(durability.shutdownNow);
        assertEquals(5, durability.timeout);
    }

    @Test
    void interruptsDurabilityAfterItsBoundedWindow() {
        RecordingExecutor operations = new RecordingExecutor(true);
        RecordingExecutor durability = new RecordingExecutor(false);

        assertFalse(FabricServerSession.stopBackgroundWorkers(
                operations, durability, 1, TimeUnit.MILLISECONDS));

        assertTrue(durability.shutdownNow);
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        private final boolean awaitResult;
        private boolean shutdown;
        private boolean shutdownNow;
        private boolean awaited;
        private long timeout;

        private RecordingExecutor(boolean awaitResult) {
            this.awaitResult = awaitResult;
        }

        @Override public void shutdown() {
            shutdown = true;
        }

        @Override public List<Runnable> shutdownNow() {
            shutdownNow = true;
            return List.of();
        }

        @Override public boolean isShutdown() {
            return shutdown || shutdownNow;
        }

        @Override public boolean isTerminated() {
            return awaitResult && isShutdown();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            awaited = true;
            this.timeout = timeout;
            return awaitResult;
        }

        @Override public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }
}
