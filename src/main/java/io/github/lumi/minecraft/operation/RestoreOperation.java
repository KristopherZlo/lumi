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
public final class RestoreOperation implements DimensionMutation {
    private final PreparedRestore restore;
    private final WorldStateApply world;
    private final BranchRefRepository refs;
    private final OperationJournalRepository journals;
    private final WorldStateApply.ApplySession targetSession;
    private final WorldStateApply.PreparedState preparedReturn;
    private final RestoreStateListener stateListener;
    private WorldStateApply.ApplySession returnSession;
    private OperationJournal journal;
    private RestoreStatus status = RestoreStatus.APPLYING;
    private boolean targetRepairAttempted;
    private boolean returnRepairAttempted;
    private ReturnPhase returnPhase;

    private RestoreOperation(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            OperationJournal journal,
            WorldStateApply.PreparedState preparedTarget,
            WorldStateApply.PreparedState preparedReturn,
            RestoreStateListener stateListener) {
        this.restore = restore;
        this.world = world;
        this.refs = refs;
        this.journals = journals;
        this.journal = journal;
        this.preparedReturn = preparedReturn;
        this.stateListener = stateListener;
        targetSession = world.begin(preparedTarget);
    }

    public static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            UUID operationId) throws IOException {
        return start(restore, world, refs, journals, operationId, RestoreStateListener.NONE);
    }

    public static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener) throws IOException {
        Objects.requireNonNull(restore, "restore");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(refs, "refs");
        Objects.requireNonNull(journals, "journals");
        Objects.requireNonNull(stateListener, "stateListener");
        WorldStateApply.PreparedState preparedTarget = world.prepare(
                new WorldStateApply.State(restore.sections(), restore.entities()));
        WorldStateApply.PreparedState preparedReturn = world.prepare(
                new WorldStateApply.State(restore.returnSections(), restore.returnEntities()));
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(),
                restore.expectedRef().commit(),
                restore.expectedRef().revision(),
                Optional.of(restore.targetCommit()),
                Optional.of(restore.expectedRef().commit()));
        OperationJournal journal = journals.create(new OperationJournal(
                Objects.requireNonNull(operationId, "operationId"),
                OperationKind.RESTORE, OperationPhase.PREPARED, target));
        return new RestoreOperation(
                restore, world, refs, journals, journal,
                preparedTarget, preparedReturn, stateListener);
    }

    public RestoreStatus tick(long deadlineNanos) throws IOException {
        switch (status) {
            case APPLYING -> applyTarget(deadlineNanos);
            case VERIFYING -> verifyTarget(deadlineNanos);
            case REPAIRING -> repairTarget(deadlineNanos);
            case RETURNING -> returnToPreviousState(deadlineNanos);
            default -> { }
        }
        return status;
    }

    private void applyTarget(long deadlineNanos) throws IOException {
        if (journal.phase() == OperationPhase.PREPARED) {
            journal = journals.advance(journal, OperationPhase.APPLYING);
        }
        if (targetSession.applyUntil(deadlineNanos)) {
            journal = journals.advance(journal, OperationPhase.VERIFYING);
            status = RestoreStatus.VERIFYING;
        }
    }

    private void verifyTarget(long deadlineNanos) throws IOException {
        switch (targetSession.verifyUntil(deadlineNanos)) {
            case IN_PROGRESS -> { }
            case VERIFIED -> completeTarget();
            case MISMATCH -> {
                if (targetRepairAttempted) {
                    beginReturn();
                } else {
                    targetRepairAttempted = true;
                    status = RestoreStatus.REPAIRING;
                }
            }
        }
    }

    private void repairTarget(long deadlineNanos) throws IOException {
        if (targetSession.repairUntil(deadlineNanos)) {
            targetSession.restartVerification();
            status = RestoreStatus.VERIFYING;
        }
    }

    private void completeTarget() throws IOException {
        refs.compareAndSet(restore.expectedRef(), restore.targetCommit());
        journal = journals.advance(journal, OperationPhase.REF_PUBLISHED);
        journal = journals.advance(journal, OperationPhase.COMPLETE);
        journals.clear(journal);
        status = RestoreStatus.COMPLETE;
        stateListener.restored(restore);
    }

    private void beginReturn() throws IOException {
        journal = journals.advance(journal, OperationPhase.ROLLING_BACK);
        returnSession = world.begin(preparedReturn);
        returnPhase = ReturnPhase.APPLYING;
        status = RestoreStatus.RETURNING;
    }

    private void returnToPreviousState(long deadlineNanos) throws IOException {
        if (returnPhase == ReturnPhase.APPLYING && returnSession.applyUntil(deadlineNanos)) {
            returnPhase = ReturnPhase.VERIFYING;
        } else if (returnPhase == ReturnPhase.VERIFYING) {
            WorldStateApply.Verification verification = returnSession.verifyUntil(deadlineNanos);
            if (verification == WorldStateApply.Verification.VERIFIED) {
                journal = journals.advance(journal, OperationPhase.COMPLETE);
                journals.clear(journal);
                status = RestoreStatus.RETURNED;
                stateListener.returned(restore);
            } else if (verification == WorldStateApply.Verification.MISMATCH) {
                if (returnRepairAttempted) {
                    journal = journals.advance(journal, OperationPhase.DEGRADED);
                    status = RestoreStatus.DEGRADED;
                } else {
                    returnRepairAttempted = true;
                    returnPhase = ReturnPhase.REPAIRING;
                }
            }
        } else if (returnPhase == ReturnPhase.REPAIRING
                && returnSession.repairUntil(deadlineNanos)) {
            returnSession.restartVerification();
            returnPhase = ReturnPhase.VERIFYING;
        }
    }

    public RestoreStatus status() {
        return status;
    }

    @Override
    public MutationTerminalState terminalState() {
        return switch (status) {
            case COMPLETE -> MutationTerminalState.SUCCEEDED;
            case RETURNED -> MutationTerminalState.RETURNED;
            case CANCELLED -> MutationTerminalState.CANCELLED;
            case DEGRADED -> MutationTerminalState.DEGRADED;
            default -> throw new IllegalStateException("Restore is not terminal");
        };
    }

    public void cancelBeforeApply() throws IOException {
        if (status != RestoreStatus.APPLYING || journal.phase() != OperationPhase.PREPARED) {
            throw new IllegalStateException("Restore has already started mutating the world");
        }
        journals.clear(journal);
        status = RestoreStatus.CANCELLED;
    }

    @Override
    public void advance(long deadlineNanos) throws IOException {
        tick(deadlineNanos);
    }

    @Override
    public boolean isTerminal() {
        return status == RestoreStatus.COMPLETE
                || status == RestoreStatus.RETURNED
                || status == RestoreStatus.CANCELLED
                || status == RestoreStatus.DEGRADED;
    }

    @Override
    public boolean isSafeToRelease() {
        return status == RestoreStatus.COMPLETE || status == RestoreStatus.RETURNED
                || status == RestoreStatus.CANCELLED;
    }

    private enum ReturnPhase {
        APPLYING,
        VERIFYING,
        REPAIRING
    }
}
