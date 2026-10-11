package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RestorePersistenceTest {
    @Test
    void slowWriteCannotOverrunTheTickDeadlineOrRunTwice() throws Exception {
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
                        write, Long.MAX_VALUE));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                long start = System.nanoTime();
                assertFalse(persistence.await(RestorePersistence.Step.CREATE,
                        () -> fail("Duplicate write"), start + 5_000_000));
                long elapsed = System.nanoTime() - start;
                System.out.println("Restore persistence blocked writer: tick wait ns=" + elapsed);
                assertTrue(elapsed < TimeUnit.MILLISECONDS.toNanos(100));
                assertTrue(persistence.isPending());
            } finally {
                release.countDown();
                persistence.drain();
            }
        }
    }
}
