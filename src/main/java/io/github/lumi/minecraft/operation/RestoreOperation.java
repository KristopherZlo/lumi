package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.OperationJournal;
import io.github.lumi.domain.model.BranchSwitchPlan;
import io.github.lumi.domain.model.BranchSwitchTarget;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.OperationKind;
import io.github.lumi.domain.model.OperationPhase;
import io.github.lumi.domain.model.OperationTarget;
import io.github.lumi.domain.model.WorkspaceSwitchPlan;
import io.github.lumi.domain.model.WorkspaceSwitchTarget;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.domain.model.ZoneRestoreTarget;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.WorldStateApply;
import io.github.lumi.storage.repository.BranchRefRepository;
import io.github.lumi.storage.repository.OperationJournalRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Advances one frozen-dimension restore without exceeding the caller's tick deadline. */
public final class RestoreOperation implements DimensionMutation {
    private static final Consumer<OperationProgress> NO_PROGRESS = ignored -> { };
    private final PreparedRestore restore;
    private final WorldStateApply world;
    private final RestorePublication publication;
    private final OperationJournalRepository journals;
    private final WorldStateApply.ApplySession targetSession;
    private final WorldStateApply.PreparedState preparedTarget;
    private final WorldStateApply.PreparedState preparedReturn;
    private final RestoreStateListener stateListener;
    private WorldStateApply.ApplySession returnSession;
    private OperationJournal journal;
    private RestoreStatus status = RestoreStatus.APPLYING;
    private IOException failure;
    private boolean targetRepairAttempted;
    private boolean returnRepairAttempted;
    private boolean returnPublicationStarted;
    private boolean journalPersisted;
    private ReturnPhase returnPhase;

    @Override public OperationProgress progress() {
        return OperationProgress.indeterminate("Restore: " + status.name().toLowerCase());
    }

    private RestoreOperation(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            OperationJournal journal,
            boolean journalPersisted,
            WorldStateApply.PreparedState preparedTarget,
            WorldStateApply.PreparedState preparedReturn,
            RestoreStateListener stateListener) {
        this.restore = restore;
        this.world = world;
        this.publication = publication;
        this.journals = journals;
        this.journal = journal;
        this.journalPersisted = journalPersisted;
        this.preparedTarget = preparedTarget;
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
        return start(restore, world, refs, journals, operationId,
                stateListener, NO_PROGRESS);
    }

    public static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(refs, "refs");
        return start(restore, world, new BranchRefRestorePublication(refs),
                journals, operationId, stateListener, OperationKind.RESTORE,
                new OperationTarget(
                        restore.expectedRef().name(), restore.expectedRef().commit(),
                        restore.expectedRef().revision(),
                        Optional.of(restore.targetCommit()),
                        Optional.of(restore.expectedRef().commit())),
                Optional.empty(), progress);
    }

    public static RestoreOperation startBranchSwitch(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            BranchSwitchPlan plan) throws IOException {
        return startBranchSwitch(
                restore, world, publication, journals, operationId,
                stateListener, plan, plan.source().commit(), Optional.empty(), NO_PROGRESS);
    }

    public static RestoreOperation startBranchSwitch(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            BranchSwitchPlan plan,
            CommitId returnPoint,
            WorkingIndexSnapshot capturedGenerations) throws IOException {
        return startBranchSwitch(
                restore, world, publication, journals, operationId,
                stateListener, plan, returnPoint, capturedGenerations, NO_PROGRESS);
    }

    public static RestoreOperation startBranchSwitch(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            BranchSwitchPlan plan,
            CommitId returnPoint,
            WorkingIndexSnapshot capturedGenerations,
            Consumer<OperationProgress> progress) throws IOException {
        return startBranchSwitch(
                restore, world, publication, journals, operationId,
                stateListener, plan, returnPoint,
                Optional.of(Objects.requireNonNull(
                        capturedGenerations, "capturedGenerations")), progress);
    }

    private static RestoreOperation startBranchSwitch(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            BranchSwitchPlan plan,
            CommitId returnPoint,
            Optional<WorkingIndexSnapshot> capturedGenerations,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(returnPoint, "returnPoint");
        if (!restore.expectedRef().equals(plan.source())
                || !restore.targetCommit().equals(plan.target().commit())) {
            throw new IOException("Prepared Restore does not match branch switch plan");
        }
        OperationTarget target = new OperationTarget(
                plan.source().name(), plan.source().commit(), plan.source().revision(),
                Optional.of(plan.target().commit()), Optional.of(returnPoint),
                Optional.of(new BranchSwitchTarget(
                        plan.target().name(), plan.target().revision(),
                        plan.expectedActive().revision())));
        return start(restore, world, publication, journals, operationId,
                stateListener, OperationKind.BRANCH_SWITCH, target,
                capturedGenerations, progress);
    }

    public static RestoreOperation startWorkspaceSwitch(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            WorkspaceSwitchPlan plan) throws IOException {
        return startWorkspaceSwitch(restore, world, publication, journals,
                operationId, stateListener, plan, NO_PROGRESS);
    }

    public static RestoreOperation startWorkspaceSwitch(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            WorkspaceSwitchPlan plan,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(plan, "plan");
        BranchSwitchPlan branch = plan.branch();
        if (!restore.expectedRef().equals(branch.source())
                || !restore.targetCommit().equals(branch.target().commit())) {
            throw new IOException("Prepared Restore does not match workspace switch plan");
        }
        OperationTarget target = new OperationTarget(
                branch.source().name(), branch.source().commit(), branch.source().revision(),
                Optional.of(branch.target().commit()), Optional.of(branch.source().commit()),
                Optional.of(new BranchSwitchTarget(
                        branch.target().name(), branch.target().revision(),
                        branch.expectedActive().revision())),
                Optional.empty(), false,
                Optional.of(new WorkspaceSwitchTarget(
                        plan.expectedActive().id(), plan.targetWorkspace(),
                        plan.expectedActive().revision())));
        return start(restore, world, publication, journals, operationId,
                stateListener, OperationKind.BRANCH_SWITCH, target,
                Optional.empty(), progress);
    }

    public static RestoreOperation startMerge(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener) throws IOException {
        return startMerge(restore, world, publication, journals,
                operationId, stateListener, NO_PROGRESS);
    }

    public static RestoreOperation startMerge(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            Consumer<OperationProgress> progress) throws IOException {
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(), restore.expectedRef().commit(),
                restore.expectedRef().revision(), Optional.of(restore.targetCommit()),
                Optional.of(restore.expectedRef().commit()));
        return start(restore, world, publication, journals, operationId,
                stateListener, OperationKind.MERGE, target,
                Optional.empty(), progress);
    }

    public static RestoreOperation startPartial(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            BlockAreaTarget area,
            CommitId returnPoint) throws IOException {
        return startPartial(restore, world, publication, journals, operationId,
                stateListener, area, returnPoint, NO_PROGRESS);
    }

    public static RestoreOperation startPartial(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            BlockAreaTarget area,
            CommitId returnPoint,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(returnPoint, "returnPoint");
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(), restore.expectedRef().commit(),
                restore.expectedRef().revision(), Optional.of(restore.targetCommit()),
                Optional.of(returnPoint), Optional.empty(),
                Optional.of(area));
        return start(restore, world, publication, journals, operationId,
                stateListener, OperationKind.RESTORE, target,
                Optional.empty(), progress);
    }

    public static RestoreOperation startZone(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            Zone zone,
            CommitId returnPoint) throws IOException {
        return startZone(restore, world, publication, journals, operationId,
                stateListener, zone, returnPoint, NO_PROGRESS);
    }

    public static RestoreOperation startZone(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            Zone zone,
            CommitId returnPoint,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(zone, "zone");
        Objects.requireNonNull(returnPoint, "returnPoint");
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(), restore.expectedRef().commit(),
                restore.expectedRef().revision(), Optional.of(restore.targetCommit()),
                Optional.of(returnPoint), Optional.empty(), Optional.empty(), false,
                Optional.empty(), Optional.of(new ZoneRestoreTarget(
                        zone.workspaceId(), zone.id(), zone.revision())));
        return start(restore, world, publication, journals, operationId,
                stateListener, OperationKind.RESTORE, target,
                Optional.empty(), progress);
    }

    public static RestoreOperation startWithoutEntities(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener) throws IOException {
        return startWithoutEntities(restore, world, refs, journals,
                operationId, stateListener, NO_PROGRESS);
    }

    public static RestoreOperation startWithoutEntities(
            PreparedRestore restore,
            WorldStateApply world,
            BranchRefRepository refs,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(refs, "refs");
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(), restore.expectedRef().commit(),
                restore.expectedRef().revision(), Optional.of(restore.targetCommit()),
                Optional.of(restore.expectedRef().commit()), Optional.empty(),
                Optional.empty(), true);
        return start(restore, world, new BranchRefRestorePublication(refs),
                journals, operationId, stateListener, OperationKind.RESTORE,
                target, Optional.empty(), progress);
    }

    public static RestoreOperation startQuickRollback(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            CommitId returnPoint,
            WorkingIndexSnapshot capturedGenerations) throws IOException {
        return startQuickRollback(restore, world, publication, journals,
                operationId, stateListener, returnPoint, capturedGenerations,
                NO_PROGRESS);
    }

    public static RestoreOperation startQuickRollback(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            CommitId returnPoint,
            WorkingIndexSnapshot capturedGenerations,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(returnPoint, "returnPoint");
        Objects.requireNonNull(capturedGenerations, "capturedGenerations");
        if (!restore.targetCommit().equals(restore.expectedRef().commit())) {
            throw new IOException("Quick Rollback target must be the active HEAD");
        }
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(), restore.expectedRef().commit(),
                restore.expectedRef().revision(), Optional.of(restore.targetCommit()),
                Optional.of(returnPoint));
        return start(restore, world, publication, journals, operationId,
                stateListener, OperationKind.QUICK_ROLLBACK, target,
                Optional.of(capturedGenerations), progress);
    }

    public static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId) throws IOException {
        return start(restore, world, publication, journals, operationId, RestoreStateListener.NONE);
    }

    /** Rebuilds idempotent apply cursors around an already durable crash journal. */
    public static RestoreOperation resume(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            OperationJournal journal,
            RestoreStateListener stateListener) throws IOException {
        return resume(restore, world, publication, journals, journal,
                stateListener, NO_PROGRESS);
    }

    /** Rebuilds idempotent apply cursors around an already durable crash journal. */
    public static RestoreOperation resume(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            OperationJournal journal,
            RestoreStateListener stateListener,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(journals, "journals");
        Objects.requireNonNull(journal, "journal");
        if (journal.kind() == OperationKind.SAVE) {
            throw new IllegalArgumentException("Save journals cannot resume as Restore");
        }
        if (!journals.read().filter(journal::equals).isPresent()) {
            throw new IOException("Recovery journal changed before Resume");
        }
        return prepare(restore, world, publication, journals, journal,
                stateListener, progress);
    }

    public static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener) throws IOException {
        OperationTarget target = new OperationTarget(
                restore.expectedRef().name(),
                restore.expectedRef().commit(),
                restore.expectedRef().revision(),
                Optional.of(restore.targetCommit()),
                Optional.of(restore.expectedRef().commit()));
        return start(restore, world, publication, journals, operationId,
                stateListener, OperationKind.RESTORE, target);
    }

    private static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            OperationKind kind,
            OperationTarget target) throws IOException {
        return start(restore, world, publication, journals, operationId,
                stateListener, kind, target, Optional.empty(), NO_PROGRESS);
    }

    private static RestoreOperation start(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            UUID operationId,
            RestoreStateListener stateListener,
            OperationKind kind,
            OperationTarget target,
            Optional<WorkingIndexSnapshot> capturedGenerations,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(restore, "restore");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(journals, "journals");
        Objects.requireNonNull(stateListener, "stateListener");
        Objects.requireNonNull(progress, "progress");
        WorldStateApply.PreparedState preparedTarget = prepareWorldState(
                world, new WorldStateApply.State(
                        restore.sections(), restore.entities(), restore.playerSpawns()),
                "Restore: decoding target", progress);
        WorldStateApply.PreparedState preparedReturn = prepareWorldState(
                world, new WorldStateApply.State(
                        restore.returnSections(), restore.returnEntities(),
                        restore.returnPlayerSpawns()),
                "Restore: decoding return point", progress);
        OperationJournal journal = new OperationJournal(
                Objects.requireNonNull(operationId, "operationId"),
                Objects.requireNonNull(kind, "kind"), OperationPhase.PREPARED,
                Objects.requireNonNull(target, "target"),
                Objects.requireNonNull(capturedGenerations, "capturedGenerations"));
        return new RestoreOperation(
                restore, world, publication, journals, journal, false,
                preparedTarget, preparedReturn, stateListener);
    }

    private static RestoreOperation prepare(
            PreparedRestore restore,
            WorldStateApply world,
            RestorePublication publication,
            OperationJournalRepository journals,
            OperationJournal journal,
            RestoreStateListener stateListener,
            Consumer<OperationProgress> progress) throws IOException {
        Objects.requireNonNull(restore, "restore");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(publication, "publication");
        Objects.requireNonNull(stateListener, "stateListener");
        WorldStateApply.PreparedState preparedTarget = prepareWorldState(
                world, new WorldStateApply.State(
                        restore.sections(), restore.entities(), restore.playerSpawns()),
                "Restore: decoding target", progress);
        WorldStateApply.PreparedState preparedReturn = prepareWorldState(
                world, new WorldStateApply.State(
                        restore.returnSections(), restore.returnEntities(),
                        restore.returnPlayerSpawns()),
                "Restore: decoding return point", progress);
        return new RestoreOperation(
                restore, world, publication, journals, journal, true,
                preparedTarget, preparedReturn, stateListener);
    }

    private static WorldStateApply.PreparedState prepareWorldState(
            WorldStateApply world,
            WorldStateApply.State state,
            String phase,
            Consumer<OperationProgress> progress) throws IOException {
        long total = (long) state.sections().size() + state.entities().size();
        progress.accept(new OperationProgress(phase, 0, total));
        return world.prepare(state, completed -> progress.accept(
                new OperationProgress(phase, completed, total)));
    }

    public RestoreStatus tick(long deadlineNanos) throws IOException {
        if (!journalPersisted) {
            journal = journals.create(journal);
            journalPersisted = true;
            return status;
        }
        switch (status) {
            case APPLYING -> applyTarget(deadlineNanos);
            case VERIFYING -> verifyTarget(deadlineNanos);
            case REPAIRING -> repairTarget(deadlineNanos);
            case PUBLISHING -> finishPublication();
            case RETURNING -> returnToPreviousState(deadlineNanos);
            default -> { }
        }
        return status;
    }

    private void applyTarget(long deadlineNanos) throws IOException {
        if (journal.phase() == OperationPhase.PREPARED) {
            journal = journals.advance(journal, OperationPhase.APPLYING);
        }
        final boolean applied;
        try {
            applied = targetSession.applyUntil(deadlineNanos);
        } catch (IOException targetFailure) {
            beginReturnAfter(targetFailure);
            return;
        }
        if (applied) {
            journal = journals.advance(journal, OperationPhase.VERIFYING);
            status = RestoreStatus.VERIFYING;
        }
    }

    private void verifyTarget(long deadlineNanos) throws IOException {
        final WorldStateApply.Verification verification;
        try {
            verification = targetSession.verifyUntil(deadlineNanos);
        } catch (IOException targetFailure) {
            beginReturnAfter(targetFailure);
            return;
        }
        switch (verification) {
            case IN_PROGRESS -> { }
            case VERIFIED -> publishTarget();
            case MISMATCH -> {
                if (targetRepairAttempted) {
                    beginReturnAfter(new IOException(
                            "Restore target still mismatched after repair"));
                } else {
                    targetRepairAttempted = true;
                    status = RestoreStatus.REPAIRING;
                }
            }
        }
    }

    private void repairTarget(long deadlineNanos) throws IOException {
        final boolean repaired;
        try {
            repaired = targetSession.repairUntil(deadlineNanos);
        } catch (IOException targetFailure) {
            beginReturnAfter(targetFailure);
            return;
        }
        if (repaired) {
            targetSession.restartVerification();
            status = RestoreStatus.VERIFYING;
        }
    }

    private void publishTarget() throws IOException {
        publication.publish(restore);
        status = RestoreStatus.PUBLISHING;
        finishPublication();
    }

    private void finishPublication() throws IOException {
        if (!publication.isDurable()) {
            return;
        }
        journal = journals.advance(journal, OperationPhase.REF_PUBLISHED);
        journal = journals.advance(journal, OperationPhase.COMPLETE);
        journals.clear(journal);
        targetSession.close();
        status = RestoreStatus.COMPLETE;
        stateListener.restored(preparedTarget.source());
    }

    private void beginReturn() throws IOException {
        targetSession.close();
        journal = journals.advance(journal, OperationPhase.ROLLING_BACK);
        returnSession = world.begin(preparedReturn);
        returnPhase = ReturnPhase.APPLYING;
        status = RestoreStatus.RETURNING;
    }

    private void beginReturnAfter(IOException targetFailure) throws IOException {
        failure = targetFailure;
        try {
            beginReturn();
        } catch (IOException returnFailure) {
            returnFailure.addSuppressed(targetFailure);
            throw returnFailure;
        }
    }

    private void returnToPreviousState(long deadlineNanos) throws IOException {
        try {
            if (returnPhase == ReturnPhase.APPLYING && returnSession.applyUntil(deadlineNanos)) {
                returnPhase = ReturnPhase.VERIFYING;
            } else if (returnPhase == ReturnPhase.VERIFYING) {
                WorldStateApply.Verification verification = returnSession.verifyUntil(deadlineNanos);
                if (verification == WorldStateApply.Verification.VERIFIED) {
                    if (!returnPublicationStarted) {
                        publication.publishReturn(restore);
                        returnPublicationStarted = true;
                    }
                    if (!publication.isReturnDurable()) {
                        return;
                    }
                    journal = journals.advance(journal, OperationPhase.COMPLETE);
                    journals.clear(journal);
                    returnSession.close();
                    status = RestoreStatus.RETURNED;
                    stateListener.returned(preparedReturn.source());
                } else if (verification == WorldStateApply.Verification.MISMATCH) {
                    if (returnRepairAttempted) {
                        var mismatch = new IOException(
                                "Restore return state still mismatched after repair");
                        mismatch.addSuppressed(failure);
                        failure = mismatch;
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
        } catch (IOException returnFailure) {
            if (failure != null && returnFailure != failure) {
                returnFailure.addSuppressed(failure);
            }
            throw returnFailure;
        }
    }

    public RestoreStatus status() {
        return status;
    }

    @Override
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
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
        try {
            if (journalPersisted) {
                journals.clear(journal);
            }
            status = RestoreStatus.CANCELLED;
        } finally {
            targetSession.close();
        }
    }

    @Override
    public boolean cancel() throws IOException {
        if (status != RestoreStatus.APPLYING
                || journal.phase() != OperationPhase.PREPARED) {
            return false;
        }
        cancelBeforeApply();
        return true;
    }

    @Override
    public void close() throws IOException {
        if (status == RestoreStatus.APPLYING
                && journal.phase() == OperationPhase.PREPARED) {
            cancelBeforeApply();
        } else {
            targetSession.close();
        }
        if (returnSession != null) {
            returnSession.close();
        }
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
