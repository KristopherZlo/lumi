package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Advances one frozen-dimension restore without exceeding the caller's tick deadline. */
public final class RestoreOperation {
    private final PreparedRestore restore;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;
    private final WorldStateApply.ApplySession targetSession;
    private OperationJournal journal;
    private RestoreStatus status = RestoreStatus.APPLYING;

    private RestoreOperation(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            OperationJournal journal) {
        this.restore = restore;
        this.refs = refs;
        this.journals = journals;
        this.journal = journal;
        targetSession = world.begin(new WorldStateApply.State(restore.sections(), restore.entities()));
    }

    public static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            UUID operationId) throws IOException {
        Objects.requireNonNull(restore, "restore");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(journals, "journals");
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(),
                restore.expectedRef().commit(),
                restore.expectedRef().revision(),
                Optional.of(restore.targetCommit()),
                Optional.of(restore.expectedRef().commit()));
        OperationJournal journal = journals.create(new OperationJournal(
                Objects.requireNonNull(operationId, "operationId"),
                OperationKind.RESTORE, OperationPhase.PREPARED, target));
        return new RestoreOperation(restore, world, refs, journals, journal);
    }

    public RestoreStatus tick(long deadlineNanos) throws IOException {
        if (status == RestoreStatus.APPLYING) {
            if (journal.phase() == OperationPhase.PREPARED) {
                journal = journals.advance(journal, OperationPhase.APPLYING);
            }
            if (targetSession.applyUntil(deadlineNanos)) {
                journal = journals.advance(journal, OperationPhase.VERIFYING);
                status = RestoreStatus.VERIFYING;
            }
        } else if (status == RestoreStatus.VERIFYING
                && targetSession.verifyUntil(deadlineNanos) == WorldStateApply.Verification.VERIFIED) {
            refs.compareAndSet(restore.expectedRef(), restore.targetCommit());
            journal = journals.advance(journal, OperationPhase.REF_PUBLISHED);
            journal = journals.advance(journal, OperationPhase.COMPLETE);
            journals.clear(journal);
            status = RestoreStatus.COMPLETE;
        }
        return status;
    }

    public RestoreStatus status() {
        return status;
    }
}
