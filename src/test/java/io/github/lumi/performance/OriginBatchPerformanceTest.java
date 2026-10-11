package io.github.lumi.performance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("performance")
class OriginBatchPerformanceTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(ints = {32, 256})
    void measuresFirstTouchCaptureThroughDurableOriginAndIndex(int sections) throws Exception {
        // Identical air origins model deduplication during ordinary new building.
        var states = Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air");
        var air = new SectionBlob(states, Map.of());
        var samples = new PerformanceSamples("durable-air-origins-" + sections, sections);
        for (int sample = -1; sample < 7; sample++) {
            var queue = new BackgroundQueue();
            Path repository = root.resolve("sample-" + sample);
            var objects = new WorldObjectRepository(repository);
            var index = new WorkingIndexRepository(repository);
            var tracker = MutationDurabilityTracker.open(objects, new OriginStore(repository), index, queue);
            PerformanceSamples.Work work = () -> {
                for (int section = 0; section < sections; section++) {
                    tracker.registerSectionMutation(new SectionKey(section, 0, 0),
                            () -> new SectionBlob(states, Map.of()));
                }
                assertTrue(queue.size() <= 2, "First-touch work must remain coalesced");
                assertFalse(tracker.canPublishChunk(0, 0));
                queue.drain();
                return tracker.snapshot().generations().size();
            };
            if (sample < 0) work.run(); // One unreported warmup with a separate repository.
            else samples.measure(work);

            // Disk rereads are outside the timed region, after all background work finishes.
            var persisted = index.read();
            assertEquals(tracker.snapshot(), persisted);
            var reopened = new OriginStore(repository);
            for (int section = 0; section < sections; section++) {
                var key = new SectionKey(section, 0, 0);
                assertEquals(air, objects.readSection(reopened.read(key).orElseThrow()));
                assertTrue(tracker.canPublishChunk(section, 0));
            }
            assertEquals(sections, persisted.generations().size());
        }
        samples.write();
    }

    private static final class BackgroundQueue implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        int size() { return tasks.size(); }
        void drain() {
            int runs = 0;
            while (!tasks.isEmpty()) {
                assertTrue(++runs <= 4, "Durability work must finish without a retry loop");
                tasks.remove().run();
            }
        }
    }
}
