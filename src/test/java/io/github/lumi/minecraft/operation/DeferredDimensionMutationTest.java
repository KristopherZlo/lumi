package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeferredDimensionMutationTest {
    @Test
    void createsCurrentOperationOnlyAfterTheQueueActivatesIt() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        OneTickMutation delegate = new OneTickMutation();
        DeferredDimensionMutation deferred = new DeferredDimensionMutation(() -> {
            creations.incrementAndGet();
            return delegate;
        });

        assertFalse(deferred.requiresFreeze());
        deferred.advance(1);
        assertEquals(1, creations.get());
        assertEquals(0, delegate.ticks);
        assertTrue(deferred.requiresFreeze());
        deferred.advance(2);
        assertTrue(deferred.isTerminal());
    }

    @Test
    void exposesFactoryFailureAsSafeTerminalFailure() throws Exception {
        IOException failure = new IOException("stale request");
        DeferredDimensionMutation deferred = new DeferredDimensionMutation(() -> {
            throw failure;
        });

        deferred.advance(1);

        assertEquals(failure, deferred.failure().orElseThrow());
        assertEquals(MutationTerminalState.FAILED, deferred.terminalState());
        assertTrue(deferred.isSafeToRelease());
    }

    @Test
    void cancelsBeforeCreatingQueuedDelegate() throws Exception {
        AtomicInteger creations = new AtomicInteger();
        DeferredDimensionMutation deferred = new DeferredDimensionMutation(() -> {
            creations.incrementAndGet();
            return new OneTickMutation();
        });

        assertTrue(deferred.cancel());

        assertEquals(0, creations.get());
        assertEquals(MutationTerminalState.CANCELLED, deferred.terminalState());
        assertTrue(deferred.isSafeToRelease());
    }

    @Test
    void closePropagatesToCreatedDelegate() throws Exception {
        OneTickMutation delegate = new OneTickMutation();
        DeferredDimensionMutation deferred = new DeferredDimensionMutation(() -> delegate);
        deferred.advance(1);

        deferred.close();

        assertEquals(1, delegate.closeCalls);
    }

    private static final class OneTickMutation implements DimensionMutation {
        private int ticks;
        private int closeCalls;

        @Override public void advance(long deadlineNanos) { ticks++; }
        @Override public boolean isTerminal() { return ticks == 1; }
        @Override public boolean isSafeToRelease() { return isTerminal(); }
        @Override public void close() { closeCalls++; }
    }
}
