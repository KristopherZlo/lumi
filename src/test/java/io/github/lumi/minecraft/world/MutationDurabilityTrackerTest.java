package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
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
        tracker.recordBuilderBlockMutation(saved, first);
        var captured = tracker.snapshot();
        long second = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBuilderBlockMutation(newer, second);

        tracker.clear(captured);

        var preview = tracker.preview(ignored -> true, 16);
        assertEquals(List.of(newer), preview.blocks());
        assertEquals(new BlockBox(0, 0, 0, 15, 15, 15),
                preview.bounds().orElseThrow());
    }

    @Test
    void pendingPreviewExcludesAmbientMutations() throws Exception {
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), command -> { });
        SectionKey ambient = new SectionKey(0, 0, 0);
        SectionKey builder = new SectionKey(2, 0, 0);
        long ambientGeneration = tracker.registerSectionMutation(
                ambient, MutationDurabilityTrackerTest::airSection);
        tracker.recordBlockMutation(
                new BlockPosition(1, 2, 3), ambientGeneration);
        long builderGeneration = tracker.registerSectionMutation(
                builder, MutationDurabilityTrackerTest::airSection);
        BlockPosition playerChange = new BlockPosition(33, 2, 3);
        tracker.recordBuilderBlockMutation(playerChange, builderGeneration);

        var preview = tracker.preview(ignored -> true, 16);

        assertEquals(1, preview.totalKeys());
        assertEquals(List.of(playerChange), preview.blocks());
        assertEquals(new BlockBox(32, 0, 0, 47, 15, 15),
                preview.bounds().orElseThrow());
        assertEquals(Map.of(ambient, ambientGeneration, builder, builderGeneration),
                tracker.snapshot().generations());
    }

    @Test
    void ambientFollowUpDoesNotHideThePlayerTouchedBlock() throws Exception {
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), command -> { });
        SectionKey key = new SectionKey(0, 0, 0);
        BlockPosition position = new BlockPosition(1, 2, 3);
        long builder = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBuilderBlockMutation(position, builder);
        long ambient = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBlockMutation(position, ambient);

        var preview = tracker.preview(ignored -> true, 16);

        assertEquals(1, preview.totalKeys());
        assertEquals(List.of(position), preview.blocks());
    }

    @Test
    void neverPublishesDirtyIndexBeforeItsOriginWhenWorkersRunOutOfOrder()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(6, 7, 8);

        long generation = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        background.runLast();

        assertTrue(new WorkingIndexRepository(repositoryRoot).read().generations().isEmpty());
        assertTrue(new OriginStore(repositoryRoot).read(key).isEmpty());
        assertFalse(tracker.canPublish(key));

        background.runNext();
        assertTrue(new OriginStore(repositoryRoot).read(key).isPresent());
        assertEquals(1, background.size());
        background.runNext();

        MutationDurabilityTracker reopened = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), Runnable::run);
        assertEquals(generation, reopened.snapshot().generations().get(key));
        assertTrue(tracker.canPublish(key));
    }

    @Test
    void clearsOnlyCapturedBuilderGenerationsAndIgnoresAmbientChanges()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(0, 0, 0);
        BlockPosition first = new BlockPosition(1, 2, 3);
        BlockPosition later = new BlockPosition(4, 5, 6);

        long builder = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBuilderBlockMutation(first, builder);
        assertTrue(tracker.hasPendingBuilderChanges());
        var captured = tracker.snapshot();

        long ambient = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBlockMutation(first, ambient);
        tracker.clear(captured);

        assertFalse(tracker.hasPendingBuilderChanges());
        assertEquals(ambient, tracker.snapshot().generations().get(key));

        long newerBuilder = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBuilderBlockMutation(later, newerBuilder);
        tracker.clear(captured);

        assertTrue(tracker.hasPendingBuilderChanges());
    }

    @Test
    void reopensAmbientDirtyWorkWithoutInventingABuilderDraft() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(1, 2, 3);
        BlockPosition position = new BlockPosition(17, 33, 49);

        long generation = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBlockMutation(position, generation);
        background.runNext();
        background.runNext();

        MutationDurabilityTracker reopened = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), Runnable::run);
        assertEquals(generation, reopened.snapshot().generations().get(key));
        assertFalse(reopened.hasPendingBuilderChanges());
    }

    @Test
    void persistsBuilderMarkerAddedAfterTheDirtyIndexWriterRan() throws Exception {
        ManualExecutor background = new ManualExecutor();
        RecordingChunkRetention retention = new RecordingChunkRetention();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background, retention);
        SectionKey key = new SectionKey(1, 2, 3);
        BlockPosition position = new BlockPosition(17, 33, 49);

        long generation = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        background.runNext();
        background.runNext();
        assertTrue(tracker.canPublishChunk(1, 3));

        tracker.recordBuilderBlockMutation(position, generation);
        assertFalse(tracker.canPublishChunk(1, 3));
        assertEquals(1, background.size());
        var boundary = tracker.durabilityBoundary();
        assertFalse(tracker.isDurable(boundary));
        background.runNext();
        assertTrue(tracker.canPublishChunk(1, 3));
        assertTrue(tracker.isDurable(boundary));

        MutationDurabilityTracker reopened = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), Runnable::run);
        assertEquals(Map.of(key, generation), reopened.builderSnapshot().generations());
    }

    @Test
    void builderBoundaryExcludesAndPreservesAmbientOnlyKeys() throws Exception {
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), command -> { });
        SectionKey ambient = new SectionKey(0, 0, 0);
        SectionKey builder = new SectionKey(1, 0, 0);
        long ambientGeneration = tracker.registerSectionMutation(
                ambient, MutationDurabilityTrackerTest::airSection);
        tracker.recordBlockMutation(new BlockPosition(1, 2, 3), ambientGeneration);
        long builderGeneration = tracker.registerSectionMutation(
                builder, MutationDurabilityTrackerTest::airSection);
        tracker.recordBuilderBlockMutation(
                new BlockPosition(17, 2, 3), builderGeneration);

        var boundary = tracker.builderSnapshot();
        assertEquals(Map.of(builder, builderGeneration), boundary.generations());
        assertEquals(boundary, tracker.builderSnapshot(builder::equals));
        assertEquals(WorkingIndexSnapshot.empty(),
                tracker.builderSnapshot(ambient::equals));

        tracker.clear(boundary);

        assertEquals(Map.of(ambient, ambientGeneration), tracker.snapshot().generations());
        assertFalse(tracker.hasPendingBuilderChanges());
    }

    @Test
    void durableClearBoundaryPreservesMutationStartedBeforeJournalCleanup() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(2, 0, 3);
        BlockPosition first = new BlockPosition(33, 2, 49);
        long generation = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBuilderBlockMutation(first, generation);
        background.runNext();
        background.runNext();
        var captured = tracker.builderSnapshot();

        MutationDurabilityTracker.IndexRevision clear = tracker.clearAndRevision(captured);
        assertFalse(tracker.isDurable(clear));
        long later = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.recordBuilderBlockMutation(new BlockPosition(34, 2, 49), later);
        assertEquals(2L, later);

        background.runNext();

        assertTrue(tracker.isDurable(clear));
        var reopened = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), Runnable::run);
        assertEquals(Map.of(key, later), reopened.snapshot().generations());
        assertEquals(Map.of(key, later), reopened.builderSnapshot().generations());
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
        long cleared = tracker.registerSectionMutation(
                key, MutationDurabilityTrackerTest::airSection);
        tracker.clear(tracker.snapshot());

        long next = tracker.markTrackedSection(key);
        assertTrue(next > cleared);
        assertEquals(next, tracker.snapshot().generations().get(key));
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
        assertFalse(tracker.canPublishChunk(4, 6));
        background.runNext();
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
        private void runLast() {
            Runnable last = null;
            int queued = tasks.size();
            for (int index = 0; index < queued; index++) {
                last = tasks.remove();
                if (index + 1 < queued) {
                    tasks.add(last);
                }
            }
            if (last == null) {
                throw new IllegalStateException("No background task is queued");
            }
            last.run();
        }
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
