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
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.RestoreService;
import io.github.lumi.domain.service.ForwardHistoryService;
import io.github.lumi.domain.service.RetentionService;
import io.github.lumi.domain.service.SaveResult;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.time.Instant;
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
    void createsReachableHiddenRefBeforePreparedRestoreJournal() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        CommitRepository commits = new CommitRepository(repositoryRoot);
        BranchRefRepository refs = new BranchRefRepository(repositoryRoot);
        OperationJournalRepository journals = new OperationJournalRepository(repositoryRoot);
        var tree = objects.write(new DimensionTree(Map.of()));
        var target = commits.write(commit(tree, List.of(), CommitKind.MANUAL));
        var main = refs.create(new BranchName("main"), target);
        var returnCommit = commits.write(commit(tree, List.of(target), CommitKind.HIDDEN_RETURN));
        var returnRef = refs.compareAndSet(main, returnCommit);
        var saved = new SaveResult(returnCommit, returnRef, new WorkingIndexSnapshot(Map.of()));
        BranchName hidden = new BranchName("hidden/return/test");
        ReturnPointRestorePreparation preparation = new ReturnPointRestorePreparation(
                new RestoreService(objects, commits, new OriginStore(repositoryRoot)),
                new NoOpWorldApply(), refs, journals,
                new ForwardHistoryService(commits, refs),
                new RetentionService(commits, refs), Runnable::run);
        var progress = new java.util.ArrayList<OperationProgress>();

        RestoreOperation operation = preparation.prepare(
                saved, target, hidden,
                UUID.fromString("10000000-0000-0000-0000-000000000001"), false,
                progress::add).join();

        assertEquals(returnCommit, refs.read(hidden).orElseThrow().commit());
        assertEquals(List.of(target), new ForwardHistoryService(commits, refs)
                .roots(new BranchName("main"), Optional.of(new UUID(1, 1))));
        assertTrue(journals.read().isEmpty());

        operation.tick(Long.MAX_VALUE);

        assertTrue(journals.read().isPresent());
        assertTrue(journals.read().orElseThrow().target().excludeEntities());
        assertEquals(RestoreStatus.APPLYING, operation.status());
        assertTrue(progress.stream().anyMatch(value ->
                value.phase().equals("Restore: preflight target")));
        assertTrue(progress.stream().anyMatch(value ->
                value.phase().equals("Restore: preflight return point")));
    }

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
        ReturnPointRestorePreparation preparation = new ReturnPointRestorePreparation(
                new RestoreService(objects, commits, new OriginStore(repositoryRoot)),
                new NoOpWorldApply(), refs, journals,
                new ForwardHistoryService(commits, refs),
                new RetentionService(commits, refs), Runnable::run);

        RestoreOperation operation = preparation.prepareCheckpoint(
                main, saved, target, UUID.randomUUID(),
                new BranchRefRestorePublication(refs), ignored -> { }).join();

        assertEquals(main, refs.read(main.name()).orElseThrow());
        assertEquals(checkpoint, refs.read(hidden).orElseThrow().commit());
        assertEquals(List.of(current), new ForwardHistoryService(commits, refs)
                .roots(new BranchName("main"), Optional.of(new UUID(1, 1))));
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

    private record Prepared(WorldStateApply.State source) implements WorldStateApply.PreparedState { }
}
