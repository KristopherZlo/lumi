package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
        MutationDurabilityTracker tracker = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot),
                new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey key = new SectionKey(3, 4, 5);
        AtomicInteger captures = new AtomicInteger();

        assertEquals(1L, tracker.registerSectionMutation(key, () -> {
            captures.incrementAndGet();
            return airSection();
        }));
        assertEquals(2L, tracker.registerSectionMutation(key, () -> {
            captures.incrementAndGet();
            return airSection();
        }));

        assertEquals(1, captures.get());
        assertEquals(2, background.size());
        assertFalse(tracker.canPublishChunk(3, 5));

        background.runNext();
        assertFalse(tracker.canPublishChunk(3, 5));
        background.runNext();

        assertTrue(tracker.canPublishChunk(3, 5));
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
}
