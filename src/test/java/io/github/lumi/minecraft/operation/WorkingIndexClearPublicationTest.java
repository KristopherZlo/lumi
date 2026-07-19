package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockPosition;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorkingIndexRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkingIndexClearPublicationTest {
    @TempDir Path repositoryRoot;

    @Test
    void keepsJournalPublicationPendingUntilExactClearIsDurable() throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
        SectionKey ambient = new SectionKey(1, 0, 1);
        SectionKey builder = new SectionKey(2, 0, 2);
        long ambientGeneration = mutations.registerSectionMutation(
                ambient, WorkingIndexClearPublicationTest::airSection);
        mutations.recordBlockMutation(new BlockPosition(17, 2, 17), ambientGeneration);
        long builderGeneration = mutations.registerSectionMutation(
                builder, WorkingIndexClearPublicationTest::airSection);
        mutations.recordBuilderBlockMutation(
                new BlockPosition(33, 2, 33), builderGeneration);
        background.runNext();
        background.runNext();
        WorkingIndexSnapshot captured = mutations.builderSnapshot();
        var publication = new WorkingIndexClearPublication(mutations, captured);

        publication.publish(null);

        assertFalse(publication.isDurable());
        assertEquals(Map.of(ambient, ambientGeneration),
                mutations.snapshot().generations());
        assertEquals(Map.of(ambient, ambientGeneration, builder, builderGeneration),
                new WorkingIndexRepository(repositoryRoot).read().generations());

        background.runNext();

        assertTrue(publication.isDurable());
        var reopened = MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), Runnable::run);
        assertEquals(Map.of(ambient, ambientGeneration), reopened.snapshot().generations());
        assertFalse(reopened.hasPendingBuilderChanges());
    }

    @Test
    void returnCheckpointRestoresClearedBuilderBoundaryAndPreservesAmbientWork()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = tracker(background);
        SectionKey ambient = new SectionKey(3, 0, 3);
        SectionKey builder = new SectionKey(4, 0, 4);
        long ambientGeneration = mutations.registerSectionMutation(
                ambient, WorkingIndexClearPublicationTest::airSection);
        mutations.recordBlockMutation(new BlockPosition(49, 2, 49), ambientGeneration);
        long builderGeneration = mutations.registerSectionMutation(
                builder, WorkingIndexClearPublicationTest::airSection);
        mutations.recordBuilderBlockMutation(
                new BlockPosition(65, 2, 65), builderGeneration);
        background.drain();
        WorkingIndexSnapshot captured = mutations.builderSnapshot();
        mutations.clearAndRevision(captured);
        background.drain();
        var publication = new WorkingIndexRecoveryPublication(
                mutations, captured,
                WorkingIndexRecoveryPublication.TargetAction.RESTORE);

        publication.publish(null);

        assertFalse(publication.isDurable());
        assertEquals(Map.of(ambient, ambientGeneration, builder, builderGeneration),
                mutations.snapshot().generations());
        background.drain();
        assertTrue(publication.isDurable());
        assertEquals(captured.generations(), mutations.builderSnapshot().generations());
    }

    @Test
    void failedReturnCheckpointRecoveryClearsBoundaryWhenWorldReturnsToHead()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = tracker(background);
        SectionKey builder = new SectionKey(5, 0, 5);
        long generation = mutations.registerSectionMutation(
                builder, WorkingIndexClearPublicationTest::airSection);
        mutations.recordBuilderBlockMutation(
                new BlockPosition(81, 2, 81), generation);
        background.drain();
        WorkingIndexSnapshot captured = mutations.builderSnapshot();
        var publication = new WorkingIndexRecoveryPublication(
                mutations, captured,
                WorkingIndexRecoveryPublication.TargetAction.RESTORE);

        publication.publishReturn(null);

        assertFalse(publication.isReturnDurable());
        background.drain();
        assertTrue(publication.isReturnDurable());
        assertEquals(WorkingIndexSnapshot.empty(), mutations.snapshot());
        assertFalse(mutations.hasPendingBuilderChanges());
    }

    @Test
    void legacyReturnReconstructsTrackingFromPreparedRestoreAfterDurableClear()
            throws Exception {
        ManualExecutor background = new ManualExecutor();
        MutationDurabilityTracker mutations = tracker(background);
        SectionKey builder = new SectionKey(6, 0, 6);
        long generation = mutations.registerSectionMutation(
                builder, WorkingIndexClearPublicationTest::airSection);
        mutations.recordBuilderBlockMutation(
                new BlockPosition(97, 2, 97), generation);
        background.drain();
        mutations.clearAndRevision(mutations.builderSnapshot());
        background.drain();
        var publication = new WorkingIndexRecoveryPublication(
                mutations, Optional.empty(),
                WorkingIndexRecoveryPublication.TargetAction.RESTORE);
        BranchRef head = new BranchRef(new BranchName("main"), id('1'), 0);
        PreparedRestore restore = new PreparedRestore(
                head, head.commit(), Map.of(builder, airSection()), Map.of(),
                Map.of(builder, airSection()), Map.of());

        publication.publish(restore);
        background.drain();

        assertTrue(publication.isDurable());
        assertTrue(mutations.builderSnapshot().generations().containsKey(builder));
        MutationDurabilityTracker reopened = tracker(new ManualExecutor());
        assertTrue(reopened.builderSnapshot().generations().containsKey(builder));
    }

    private MutationDurabilityTracker tracker(ManualExecutor background) throws Exception {
        return MutationDurabilityTracker.open(
                new WorldObjectRepository(repositoryRoot), new OriginStore(repositoryRoot),
                new WorkingIndexRepository(repositoryRoot), background);
    }

    private static SectionBlob airSection() {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }

    private static final class ManualExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable command) { tasks.add(command); }
        private void runNext() { tasks.remove().run(); }
        private void drain() {
            while (!tasks.isEmpty()) {
                runNext();
            }
        }
    }
}
