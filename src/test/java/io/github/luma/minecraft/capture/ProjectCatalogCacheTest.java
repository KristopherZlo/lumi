package io.github.luma.minecraft.capture;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectCatalogCacheTest {

    @Test
    void repeatedReadsDoNotReloadUntilInvalidated() throws Exception {
        ProjectCatalogCache<String> cache = new ProjectCatalogCache<>();
        AtomicInteger loads = new AtomicInteger();

        assertEquals("value-1", cache.getOrLoad("server", () -> "value-" + loads.incrementAndGet()));
        assertEquals("value-1", cache.getOrLoad("server", () -> "value-" + loads.incrementAndGet()));
        assertEquals(1, loads.get());

        cache.invalidate("server");

        assertEquals("value-2", cache.getOrLoad("server", () -> "value-" + loads.incrementAndGet()));
        assertEquals(2, loads.get());
    }
}
