package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RestorePersistenceTest {
    @Test
    void slowWriteDoesNotWaitOnTheTickOrRunTwice() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var persistence = new RestorePersistence(worker);
            RestorePersistence.Write write = () -> {
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                }
            };
            try {
                assertFalse(persistence.await(RestorePersistence.Step.CREATE,
                        write));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTimeoutPreemptively(java.time.Duration.ofSeconds(1), () ->
                        assertFalse(persistence.await(RestorePersistence.Step.CREATE,
                                () -> fail("Duplicate write"))));
                assertTrue(persistence.isPending());
            } finally {
                release.countDown();
                persistence.drain();
            }
        }
    }
}
