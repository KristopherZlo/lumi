package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MutationDurabilityTrackerTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void coalescesRepeatedMutationAndBlocksChunkUntilOriginAndLatestGenerationAreDurable()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        RecordingChunkRetention retention = new RecordingChunkRetention();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot),
                new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background, retention);
        SectionKey key = new SectionKey(3, 4, 5);
        AtomicInteger captures = new AtomicInteger();

        assertTrue(tracker.needsOrigin(key));
        assertEquals(1L, tracker.registerSectionMutation(key, () -> {
            captures.incrementAndGet();
            return airSection();
        }));
        assertEquals(2L, tracker.registerSectionMutation(key, () -> {
            captures.incrementAndGet();
            return airSection();
        }));

        assertEquals(1, captures.get());
        assertFalse(tracker.needsOrigin(key));
        assertEquals(2, background.size());
        assertFalse(tracker.canPublishChunk(3, 5));
        assertEquals(List.of("retain 3,5"), retention.events);

        background.runNext();
        assertFalse(tracker.canPublishChunk(3, 5));
        background.runNext();

        assertTrue(tracker.canPublishChunk(3, 5));
        assertEquals(List.of("retain 3,5", "release 3,5"), retention.events);
        assertEquals(2L, new WorkingIndexRepository(repositoryRoot).read()
                .generations().get(key));
        assertTrue(new OriginStore(repositoryRoot).read(key).isPresent());

        tracker.registerSectionMutation(key, () -> {
            throw new AssertionError("A durable origin must not be captured again");
        });
        assertFalse(tracker.canPublishChunk(3, 5));
        assertEquals(1, background.size());
        background.runNext();
        assertTrue(tracker.canPublishChunk(3, 5));
        assertEquals(List.of(
                "retain 3,5", "release 3,5",
                "retain 3,5", "release 3,5"), retention.events);
    }

    @Test
    void publishedCommitCanSatisfyDirtyGenerationButNeverMissingOrigin() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(7, 8, 9);

        tracker.registerSectionMutation(key, MutationDurabilityTrackerTest::airSection);
        tracker.clear(tracker.snapshot());

        assertFalse(tracker.canPublishChunk(7, 9));
        background.runNext();
        assertTrue(tracker.canPublishChunk(7, 9));
        background.runNext();

        assertTrue(tracker.canPublishChunk(7, 9));
        assertTrue(new WorkingIndexRepository(repositoryRoot).read().generations().isEmpty());
    }

    @Test
    void previewsBlocksAndKeepsOnlyMutationsNewerThanTheCapturedGeneration()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(0, 0, 0);
        BlockPosition saved = new BlockPosition(1, 2, 3);
        BlockPosition newer = new BlockPosition(4, 5, 6);
        long first = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBlockMutation(saved, first);
        var captured = tracker.snapshot();
        long second = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBlockMutation(newer, second);

        tracker.clear(captured);

        var preview = tracker.preview(ignored -> true, 16);
        assertEquals(List.of(newer), preview.blocks());
        assertEquals(new BlockBox(0, 0, 0, 15, 15, 15),
                preview.bounds().orElseThrow());
    }

    @Test
    void drainsManyDistinctOriginsThroughOneBoundedBackgroundTask() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);

        tracker.registerSectionMutation(new SectionKey(0, 0, 0), MutationDurabilityTrackerTest::airSection);
        tracker.registerSectionMutation(new SectionKey(0, 1, 0), MutationDurabilityTrackerTest::airSection);
        tracker.registerSectionMutation(new SectionKey(1, 0, 0), MutationDurabilityTrackerTest::airSection);

        assertEquals(2, background.size());
        background.runNext();
        background.runNext();
        assertTrue(tracker.canPublishChunk(0, 0));
        assertTrue(tracker.canPublishChunk(1, 0));
    }

    @Test
    void marksOnlyCoordinatesWhoseOriginWasAlreadyCaptured() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(2, 3, 4);

        assertThrows(IllegalStateException.class, () -> tracker.markTrackedSection(key));
        tracker.registerSectionMutation(key, MutationDurabilityTrackerTest::airSection);
        tracker.clear(tracker.snapshot());

        assertEquals(1L, tracker.markTrackedSection(key));
        assertEquals(1L, tracker.snapshot().generations().get(key));
    }

    @Test
    void retriesFailedOriginWriteWithoutRecapturingTheOrigin() throws Exception {
        Path objectsPath = repositoryRoot.resolve("objects");
        Files.writeString(objectsPath, "temporarily unavailable");
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(4, 5, 6);
        AtomicInteger captures = new AtomicInteger();

        tracker.registerSectionMutation(key, () -> {
            captures.incrementAndGet();
            return airSection();
        });
        background.runNext();
        background.runNext();
        assertFalse(tracker.canPublishChunk(4, 6));

        Files.delete(objectsPath);
        tracker.retryFailedWrites();
        background.runNext();

        assertEquals(1, captures.get());
        assertTrue(tracker.canPublishChunk(4, 6));
        assertTrue(new OriginStore(repositoryRoot).read(key).isPresent());
    }

    @Test
    void retriesFailedWorkingIndexWriteAtTheLatestGeneration() throws Exception {
        Path workingPath = repositoryRoot.resolve("working");
        Files.writeString(workingPath, "temporarily unavailable");
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(7, 8, 9);

        tracker.registerSectionMutation(key, MutationDurabilityTrackerTest::airSection);
        tracker.registerSectionMutation(key, MutationDurabilityTrackerTest::airSection);
        background.runNext();
        background.runNext();
        assertFalse(tracker.canPublishChunk(7, 9));

        Files.delete(workingPath);
        tracker.retryFailedWrites();
        background.runNext();

        assertEquals(2L, new WorkingIndexRepository(repositoryRoot).read()
                .generations().get(key));
        assertTrue(tracker.canPublishChunk(7, 9));
    }

    private static SectionBlob airSection() {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private int size() { return tasks.size(); }
        private void runNext() { tasks.remove().run(); }
    }

    private static final class RecordingChunkRetention implements ChunkDurabilityRetention {
        private final List<String> events = new ArrayList<>();

        @Override
        public void retain(int chunkX, int chunkZ) {
            events.add("retain " + chunkX + "," + chunkZ);
        }

        @Override
        public void release(int chunkX, int chunkZ) {
            events.add("release " + chunkX + "," + chunkZ);
        }
    }
}
