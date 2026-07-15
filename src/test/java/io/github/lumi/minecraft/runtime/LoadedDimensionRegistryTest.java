package io.github.lumi.minecraft.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LoadedDimensionRegistryTest {
    @Test
    void ownsExactlyOneRuntimePerLoadedDimensionAndClosesItOnUnload() throws Exception {
        LoadedDimensionRegistry<String, RecordingRuntime> registry = new LoadedDimensionRegistry<>();
        RecordingRuntime overworld = new RecordingRuntime();

        registry.load("overworld", overworld);

        assertEquals(overworld, registry.require("overworld"));
        assertThrows(IllegalStateException.class,
                () -> registry.load("overworld", new RecordingRuntime()));
        registry.unload("overworld");
        assertEquals(1, overworld.closeCalls.get());
        assertTrue(registry.find("overworld").isEmpty());
    }

    @Test
    void closesEveryRemainingRuntimeAtServerStop() throws Exception {
        LoadedDimensionRegistry<String, RecordingRuntime> registry = new LoadedDimensionRegistry<>();
        RecordingRuntime first = new RecordingRuntime();
        RecordingRuntime second = new RecordingRuntime();
        registry.load("first", first);
        registry.load("second", second);

        registry.close();

        assertEquals(1, first.closeCalls.get());
        assertEquals(1, second.closeCalls.get());
        assertTrue(registry.isEmpty());
    }

    @Test
    void returnsStableSnapshotForServerWideTicking() throws Exception {
        LoadedDimensionRegistry<String, RecordingRuntime> registry = new LoadedDimensionRegistry<>();
        RecordingRuntime first = new RecordingRuntime();
        RecordingRuntime second = new RecordingRuntime();
        registry.load("first", first);
        registry.load("second", second);

        List<RecordingRuntime> loaded = registry.loadedValues();
        registry.unload("first");

        assertEquals(2, loaded.size());
        assertTrue(loaded.contains(first));
        assertTrue(loaded.contains(second));
        assertEquals(List.of(second), registry.loadedValues());
    }

    private static final class RecordingRuntime implements AutoCloseable {
        private final AtomicInteger closeCalls = new AtomicInteger();
        @Override public void close() { closeCalls.incrementAndGet(); }
    }
}
