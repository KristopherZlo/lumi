package io.github.luma.minecraft.world;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldOperationSafetyBoundaryTest {

    @Test
    void returnsTrueWhenActionCompletes() {
        AtomicInteger calls = new AtomicInteger();

        boolean completed = WorldOperationSafetyBoundary.run(
                "exact-replay-guard",
                "restore-version",
                calls::incrementAndGet
        );

        assertTrue(completed);
        assertEquals(1, calls.get());
    }

    @Test
    void catchesRuntimeExceptionWithoutRethrowing() {
        AtomicInteger calls = new AtomicInteger();

        boolean completed = WorldOperationSafetyBoundary.run(
                "exact-replay-guard",
                "restore-version",
                () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("boom");
                }
        );

        assertFalse(completed);
        assertEquals(1, calls.get());
    }
}
