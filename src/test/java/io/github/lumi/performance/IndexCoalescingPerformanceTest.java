package io.github.lumi.performance;

import static org.junit.jupiter.api.Assertions.*;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.minecraft.world.ChunkDurabilityRetention;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("performance")
class IndexCoalescingPerformanceTest {
    @TempDir Path root;

    @Test
    void measuresForcedWritesAndExactDurabilityForRepeatedMutationBursts() throws Exception {
        var immediate = new PerformanceSamples("index-burst-immediate", 32);
        var coalesced = new PerformanceSamples("index-burst-coalesced", 32);
        var forces = new StringBuilder("sample,immediate_forces,coalesced_forces\n");
        for (int sample = 0; sample < 3; sample++) {
            long before = runBurst(root.resolve("immediate-" + sample), 0, immediate);
            long after = runBurst(root.resolve("coalesced-" + sample), 50, coalesced);
            assertEquals(32, before, "Every immediate generation must have a forced write");
            assertTrue(after > 0 && after < before, "Coalescing must reduce actual force calls");
            forces.append(sample).append(',').append(before).append(',').append(after).append('\n');
        }
        immediate.write();
        coalesced.write();
        Files.writeString(PerformanceSamples.reportPath("index-burst-forces.csv"), forces);
    }

    private long runBurst(Path repository, long delayMillis, PerformanceSamples samples) throws Exception {
        var objects = new WorldObjectRepository(repository);
        var origins = new OriginStore(repository);
        var index = new WorkingIndexRepository(repository);
        var key = new SectionKey(0, 0, 0);
        var seeded = MutationDurabilityTracker.open(objects, origins, index, Runnable::run);
        seeded.registerSectionMutation(key, () -> new SectionBlob(
                Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air"), Map.of()));
        var tasks = new ConcurrentLinkedQueue<Runnable>();
        var tracker = MutationDurabilityTracker.open(objects, origins, index, tasks::add,
                ChunkDurabilityRetention.NONE, delayMillis);
        Path recordingPath = repository.resolve("forces.jfr");
        try (var recording = new Recording()) {
            recording.enable("jdk.FileForce").withThreshold(Duration.ZERO).withoutStackTrace();
            recording.start();
            samples.measure(() -> {
                for (int mutation = 0; mutation < 32; mutation++) {
                    tracker.markTrackedSection(key);
                    drain(tasks);
                }
                var boundary = tracker.durabilityBoundary();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                do {
                    drain(tasks);
                    Thread.onSpinWait();
                } while (!tracker.isDurable(boundary) && System.nanoTime() < deadline);
                assertTrue(tracker.isDurable(boundary));
                return tracker.snapshot().generations().get(key);
            });
            recording.stop();
            recording.dump(recordingPath);
        }
        assertEquals(33L, index.read().generations().get(key));
        assertTrue(tracker.canPublishChunk(0, 0));
        return RecordingFile.readAllEvents(recordingPath).stream()
                .filter(event -> event.getEventType().getName().equals("jdk.FileForce"))
                .filter(event -> Path.of(event.getString("path")).toAbsolutePath()
                        .startsWith(repository.resolve("working").toAbsolutePath())).count();
    }

    private static void drain(ConcurrentLinkedQueue<Runnable> tasks) {
        Runnable task;
        while ((task = tasks.poll()) != null) task.run();
    }
}
