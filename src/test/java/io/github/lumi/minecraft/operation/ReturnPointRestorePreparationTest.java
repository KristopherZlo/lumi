package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.BlockOnlyRestoreService;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.ForwardHistoryService;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReturnPointRestorePreparationTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void keepsSourceRefUntilCheckpointedRestorePublishes() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var target = commits.write(commit(tree, List.of(), CommitKind.MANUAL));
        var current = commits.write(commit(tree, List.of(target), CommitKind.MANUAL));
        var main = refs.create(new BranchName("main"), current);
        BranchName hidden = new BranchName("hidden/return/zz-current");
        var checkpoint = commits.write(
                commit(tree, List.of(current), CommitKind.HIDDEN_RETURN));
        var hiddenRef = refs.create(hidden, checkpoint);
        var captured = new WorkingIndexSnapshot(Map.of(new SectionKey(1, 2, 3), 7L));
        var saved = new SaveResult(checkpoint, hiddenRef, captured);
        OriginStore origins = new OriginStore(repositoryRoot);
        ReturnPointRestorePreparation preparation = new ReturnPointRestorePreparation(
                new RestoreService(objects, commits, origins),
                new BlockOnlyRestoreService(objects, commits, origins),
                new NoOpWorldApply(), refs, journals,
                new ForwardHistoryService(commits, refs), Runnable::run);
        var progress = new ArrayList<OperationProgress>();

        RestoreOperation operation = preparation.prepareBlockOnlyCheckpoint(
                main, saved, target, new CommitAuthor(new UUID(0, 2), "Builder"),
                Instant.EPOCH, UUID.randomUUID(),
                new BranchRefRestorePublication(refs), progress::add).join();

        assertEquals(main, refs.read(main.name()).orElseThrow());
        assertEquals(checkpoint, refs.read(hidden).orElseThrow().commit());
        assertEquals(List.of(current), new ForwardHistoryService(commits, refs)
                .roots(new BranchName("main"), Optional.of(new UUID(1, 1))));
        assertTrue(progress.stream().anyMatch(value ->
                value.phase().equals("Restore: preflight target")));
        assertTrue(progress.stream().anyMatch(value ->
                value.phase().equals("Restore: preflight return point")));
        operation.tick(Long.MAX_VALUE);

        var journal = journals.read().orElseThrow();
        assertEquals(current, journal.target().expectedHead());
        assertEquals(Optional.of(checkpoint), journal.target().returnPoint());
        assertEquals(Optional.of(captured), journal.capturedGenerations());
        assertEquals(main, refs.read(main.name()).orElseThrow());

        operation.tick(Long.MAX_VALUE);
        operation.tick(Long.MAX_VALUE);

        assertEquals(RestoreStatus.COMPLETE, operation.status());
        assertEquals(target, refs.read(main.name()).orElseThrow().commit());
        assertEquals(checkpoint, refs.read(hidden).orElseThrow().commit());
        assertTrue(journals.read().isEmpty());

        CompletionException stale = assertThrows(CompletionException.class, () ->
                preparation.prepareCheckpoint(
                        main, saved, target, UUID.randomUUID(),
                        new BranchRefRestorePublication(refs), ignored -> { }).join());
        assertTrue(stale.getCause() instanceof java.io.IOException);
    }

    @Test
    void reusesPrewarmedWorldPlanForAnEquivalentCheckpoint() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var target = commits.write(commit(tree, List.of(), CommitKind.MANUAL));
        var current = commits.write(commit(tree, List.of(target), CommitKind.MANUAL));
        var source = refs.create(new BranchName("main"), current);
        var checkpoint = commits.write(
                commit(tree, List.of(current), CommitKind.HIDDEN_RETURN));
        var saved = new SaveResult(
                checkpoint,
                refs.create(new BranchName("hidden/return/prewarm"), checkpoint),
                WorkingIndexSnapshot.empty());
        CountingWorldApply world = new CountingWorldApply();
        ReturnPointRestorePreparation preparation = new ReturnPointRestorePreparation(
                new RestoreService(objects, commits, new OriginStore(repositoryRoot)),
                new BlockOnlyRestoreService(
                        objects, commits, new OriginStore(repositoryRoot)),
                world, refs, journals, new ForwardHistoryService(commits, refs),
                Runnable::run);
        RestorePrewarm prewarm = preparation.prewarmCheckpoint(
                source, target, ignored -> { });

        RestoreOperation operation = preparation.prepareCheckpoint(
                source, saved, target, UUID.randomUUID(),
                new BranchRefRestorePublication(refs), ignored -> { }, prewarm).join();

        assertEquals(2, world.prepareCalls);
        assertEquals(1, world.beginCalls);
        operation.close();

        var changedCheckpoint = commits.write(new Commit(
                tree, List.of(current), new CommitAuthor(new UUID(0, 0), "Lumi"),
                "Return", Instant.EPOCH, new UUID(1, 1), Optional.empty(),
                CommitKind.HIDDEN_RETURN, new CommitStatistics(0, 0, 0, 0),
                Map.of(new UUID(9, 9), new PlayerSpawn(1, 2, 3, 0, 0, false))));
        var changedSave = new SaveResult(
                changedCheckpoint,
                refs.create(new BranchName("hidden/return/stale"), changedCheckpoint),
                WorkingIndexSnapshot.empty());
        RestorePrewarm stale = preparation.prewarmCheckpoint(
                source, target, ignored -> { });

        RestoreOperation fallback = preparation.prepareCheckpoint(
                source, changedSave, target, UUID.randomUUID(),
                new BranchRefRestorePublication(refs), ignored -> { }, stale).join();

        assertEquals(6, world.prepareCalls);
        assertEquals(2, world.beginCalls);
        fallback.close();
    }

    private static Commit commit(
            io.github.lumi.domain.model.ObjectId tree,
            List<io.github.lumi.domain.model.CommitId> parents,
            CommitKind kind) {
        return new Commit(
                tree, parents, new CommitAuthor(new UUID(0, 0), "Lumi"),
                "Return", Instant.EPOCH, new UUID(1, 1), Optional.empty(), kind,
                new CommitStatistics(0, 0, 0, 0));
    }

    private static final class NoOpWorldApply implements WorldStateApply {
        @Override public PreparedState prepare(State target) { return new Prepared(target); }
        @Override public ApplySession begin(PreparedState target) {
            return new ApplySession() {
                @Override public boolean applyUntil(long deadlineNanos) { return true; }
                @Override public Verification verifyUntil(long deadlineNanos) {
                    return Verification.VERIFIED;
                }
                @Override public boolean persistUntil(long deadlineNanos) { return true; }
                @Override public boolean repairUntil(long deadlineNanos) { return true; }
                @Override public void restartVerification() { }
            };
        }
    }

    private static final class CountingWorldApply implements WorldStateApply {
        private int prepareCalls;
        private int beginCalls;

        @Override public PreparedState prepare(State target) {
            prepareCalls++;
            return new Prepared(target);
        }

        @Override public ApplySession begin(PreparedState target) {
            beginCalls++;
            return new NoOpWorldApply().begin(target);
        }
    }

    private record Prepared(WorldStateApply.State source) implements WorldStateApply.PreparedState { }
}
