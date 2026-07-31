package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DeadlineFutureTest {
    @Test
    void keepsUnboundedHarnessDeadlinesNonBlocking() throws Exception {
        CompletableFuture<Void> pending = new CompletableFuture<>();

        assertFalse(DeadlineFuture.await(pending, Long.MAX_VALUE));
        pending.complete(null);
        assertTrue(DeadlineFuture.await(pending, Long.MAX_VALUE));
    }
}
