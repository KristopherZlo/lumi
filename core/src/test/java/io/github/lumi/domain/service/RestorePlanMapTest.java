package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RestorePlanMapTest {
    @Test
    void resolvesOnlyValuesThatAreRead() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var plan = new RestorePlanMap<>(
                Set.of("a", "b"),
                key -> {
                    reads.incrementAndGet();
                    return key.toUpperCase();
                },
                closes::incrementAndGet);

        assertEquals(2, plan.size());
        assertEquals(0, reads.get());
        assertEquals("A", plan.get("a"));
        assertEquals(1, reads.get());
        assertEquals(2, plan.materialize().size());
        assertEquals(3, reads.get());
        plan.close();
        assertEquals(1, closes.get());
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

    @Test
    void preservesSuppliedReadOrder() {
        var plan = new RestorePlanMap<>(
                new LinkedHashSet<>(List.of("second", "first")),
                String::toUpperCase);

        assertEquals(List.of("second", "first"),
                plan.entrySet().stream().map(java.util.Map.Entry::getKey).toList());
    }
}
