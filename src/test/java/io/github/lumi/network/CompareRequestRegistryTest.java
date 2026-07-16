package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.ComparisonSummary;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CompareRequestRegistryTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID OTHER_PLAYER = new UUID(0, 2);

    @Test
    void reservesRequestBeforeLaunchingBackgroundCompare() throws Exception {
        var registry = new CompareRequestRegistry();
        UUID request = new UUID(0, 3);
        AtomicInteger launches = new AtomicInteger();
        registry.start(request, PLAYER, cancelled -> {
            launches.incrementAndGet();
            return new CompletableFuture<>();
        });

        assertThrows(IllegalStateException.class,
                () -> registry.start(request, PLAYER, cancelled -> {
                    launches.incrementAndGet();
                    return new CompletableFuture<>();
                }));

        assertEquals(1, launches.get());
    }

    @Test
    void onlyOwnerMayCancelAndCompletionPublishesOnce() throws Exception {
        var registry = new CompareRequestRegistry();
        UUID request = new UUID(0, 4);
        CompletableFuture<ComparisonSummary> future = new CompletableFuture<>();
        var job = registry.start(request, PLAYER, cancelled -> future);

        assertFalse(registry.cancelOwned(request, OTHER_PLAYER));
        assertFalse(job.cancelled().get());
        assertTrue(registry.finish(request, job));
        assertFalse(registry.finish(request, job));
        assertFalse(future.isCancelled());
    }

    @Test
    void playerAndServerCleanupCancelOutstandingWork() throws Exception {
        var registry = new CompareRequestRegistry();
        var owned = registry.start(
                new UUID(0, 5), PLAYER, cancelled -> new CompletableFuture<>());
        var other = registry.start(
                new UUID(0, 6), OTHER_PLAYER, cancelled -> new CompletableFuture<>());

        registry.cancelPlayer(PLAYER);

        assertTrue(owned.cancelled().get());
        assertTrue(owned.future().isCancelled());
        assertFalse(other.cancelled().get());

        registry.clear();

        assertTrue(other.cancelled().get());
        assertTrue(other.future().isCancelled());
    }
}
