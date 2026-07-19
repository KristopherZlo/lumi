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
    void waitsForOperationProducersBeforeDrainingDurability() {
        RecordingExecutor operations = new RecordingExecutor(true);
        RecordingExecutor durability = new RecordingExecutor(true);

        assertTrue(FabricServerSession.stopBackgroundWorkers(
                operations, durability, 5, TimeUnit.SECONDS));

        assertTrue(operations.shutdownNow);
        assertTrue(operations.awaited);
        assertTrue(durability.shutdown);
        assertTrue(durability.awaited);
        assertFalse(durability.shutdownNow);
        assertEquals(TimeUnit.NANOSECONDS, operations.awaitUnit);
        assertEquals(TimeUnit.NANOSECONDS, durability.awaitUnit);
    }

    @Test
    void interruptsDurabilityAfterItsBoundedWindow() {
        RecordingExecutor operations = new RecordingExecutor(true);
        RecordingExecutor durability = new RecordingExecutor(false);

        assertFalse(FabricServerSession.stopBackgroundWorkers(
                operations, durability, 1, TimeUnit.MILLISECONDS));

        assertTrue(durability.shutdownNow);
    }

    @Test
    void reportsIncompleteShutdownWhenOperationProducerIgnoresInterruption() {
        RecordingExecutor operations = new RecordingExecutor(false);
        RecordingExecutor durability = new RecordingExecutor(true);

        assertFalse(FabricServerSession.stopBackgroundWorkers(
                operations, durability, 1, TimeUnit.MILLISECONDS));

        assertTrue(operations.shutdownNow);
        assertTrue(operations.awaited);
        assertTrue(durability.shutdown);
        assertTrue(durability.awaited);
    }

    private static final class RecordingExecutor extends AbstractExecutorService {
        private final boolean awaitResult;
        private boolean shutdown;
        private boolean shutdownNow;
        private boolean awaited;
        private TimeUnit awaitUnit;

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
            awaitUnit = unit;
            return awaitResult;
        }

        @Override public void execute(Runnable command) {
            throw new UnsupportedOperationException();
        }
    }
}
