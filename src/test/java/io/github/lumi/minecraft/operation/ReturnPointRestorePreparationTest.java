package io.github.lumi.minecraft.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.RestoreService;
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
                new NoOpWorldApply(), refs, journals, Runnable::run);

        RestoreOperation operation = preparation.prepare(
                saved, target, hidden,
                UUID.fromString("10000000-0000-0000-0000-000000000001"), false).join();

        assertEquals(returnCommit, refs.read(hidden).orElseThrow().commit());
        assertTrue(journals.read().isPresent());
        assertTrue(journals.read().orElseThrow().target().excludeEntities());
        assertEquals(RestoreStatus.APPLYING, operation.status());
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
                @Override public boolean repairUntil(long deadlineNanos) { return true; }
                @Override public void restartVerification() { }
            };
        }
    }

    private record Prepared(WorldStateApply.State state) implements WorldStateApply.PreparedState { }
}
