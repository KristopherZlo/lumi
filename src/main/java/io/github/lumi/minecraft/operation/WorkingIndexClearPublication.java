package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import java.util.Objects;

/** Clears one immutable generation boundary before a Restore journal can close. */
public final class WorkingIndexClearPublication implements RestorePublication {
    private final MutationDurabilityTracker mutations;
    private final WorkingIndexSnapshot captured;
    private MutationDurabilityTracker.IndexRevision revision;

    public WorkingIndexClearPublication(
            MutationDurabilityTracker mutations,
            WorkingIndexSnapshot captured) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
        this.captured = Objects.requireNonNull(captured, "captured");
    }

    @Override
    public void publish(PreparedRestore ignored) {
        if (revision != null) {
            throw new IllegalStateException("Working-index boundary was already published");
        }
        revision = mutations.clearAndRevision(captured);
    }

    @Override
    public boolean isDurable() {
        return revision != null && mutations.isDurable(revision);
    }
}
