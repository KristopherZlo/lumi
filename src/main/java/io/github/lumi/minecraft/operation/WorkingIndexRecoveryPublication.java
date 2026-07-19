package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;

/** Reconciles Quick Rollback pending state for either verified recovery direction. */
public final class WorkingIndexRecoveryPublication implements RestorePublication {
    public enum TargetAction { CLEAR, RESTORE }

    private final MutationDurabilityTracker mutations;
    private final Optional<WorkingIndexSnapshot> captured;
    private final TargetAction targetAction;
    private MutationDurabilityTracker.IndexRevision revision;

    public WorkingIndexRecoveryPublication(
            MutationDurabilityTracker mutations,
            WorkingIndexSnapshot captured,
            TargetAction targetAction) {
        this(mutations, Optional.of(captured), targetAction);
    }

    public WorkingIndexRecoveryPublication(
            MutationDurabilityTracker mutations,
            Optional<WorkingIndexSnapshot> captured,
            TargetAction targetAction) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.captured = Objects.requireNonNull(captured, "captured");
        this.targetAction = Objects.requireNonNull(targetAction, "targetAction");
    }

    @Override
    public void publish(PreparedRestore restore) {
        apply(targetAction, restore);
    }

    @Override
    public boolean isDurable() {
        return isAppliedAndDurable();
    }

    @Override
    public void publishReturn(PreparedRestore restore) {
        apply(targetAction == TargetAction.CLEAR
                ? TargetAction.RESTORE : TargetAction.CLEAR, restore);
    }

    @Override
    public boolean isReturnDurable() {
        return isAppliedAndDurable();
    }

    private void apply(TargetAction action, PreparedRestore restore) {
        if (revision != null) {
            throw new IllegalStateException("Recovery working-index boundary was already published");
        }
        WorkingIndexSnapshot boundary = captured.orElseGet(mutations::builderSnapshot);
        if (action == TargetAction.CLEAR) {
            revision = mutations.clearAndRevision(boundary);
        } else if (captured.isPresent() || !boundary.generations().isEmpty()) {
            revision = mutations.restoreAndRevision(boundary);
        } else {
            Objects.requireNonNull(restore, "restore");
            var keys = new ArrayList<io.github.lumi.domain.model.HistoryKey>(
                    restore.sections().size() + restore.entities().size());
            keys.addAll(restore.sections().keySet());
            keys.addAll(restore.entities().keySet());
            revision = mutations.trackRestoredBuilderAndRevision(keys);
        }
    }

    private boolean isAppliedAndDurable() {
        return revision != null && mutations.isDurable(revision);
    }
}
