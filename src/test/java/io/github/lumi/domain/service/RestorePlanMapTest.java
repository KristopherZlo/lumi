package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RestorePlanMapTest {
    @Test
    void resolvesOnlyValuesThatAreRead() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var plan = new RestorePlanMap<>(Set.of("a", "b"), key -> {
            reads.incrementAndGet();
            return key.toUpperCase();
        });

        assertEquals(2, plan.size());
        assertEquals(0, reads.get());
        assertEquals("A", plan.get("a"));
        assertEquals(1, reads.get());
        assertEquals(2, plan.materialize().size());
        assertEquals(3, reads.get());
    }

    @Test
    void preservesCheckedStorageFailureForWorkflowBoundary() {
        var plan = new RestorePlanMap<>(Set.of("a"), key -> {
            throw new IOException("missing object");
        });

        assertThrows(UncheckedIOException.class, () -> plan.get("a"));
        IOException failure = assertThrows(IOException.class, plan::materialize);
        assertEquals("missing object", failure.getMessage());
    }
}
