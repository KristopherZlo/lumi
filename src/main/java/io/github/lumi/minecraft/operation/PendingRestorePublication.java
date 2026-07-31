package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Publishes an applied partial Restore as ordinary pending world work. */
public final class PendingRestorePublication implements RestorePublication {
    private final MutationDurabilityTracker mutations;
    private MutationDurabilityTracker.DurabilityBoundary boundary;

    public PendingRestorePublication(MutationDurabilityTracker mutations) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
    }

    @Override
    public void publish(PreparedRestore restore) throws IOException {
        if (boundary != null) {
            throw new IllegalStateException("Pending Restore was already published");
        }
        Map<HistoryKey, Long> generations = new HashMap<>();
        try {
            for (var key : restore.sections().keySet()) {
                var before = restore.returnSections().get(key);
                generations.put(key,
                        mutations.registerSectionMutation(key, () -> before));
            }
            for (var key : restore.entities().keySet()) {
                var before = restore.returnEntities().get(key);
                generations.put(key,
                        mutations.registerEntityMutation(key, () -> before));
            }
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
        boundary = mutations.durabilityBoundary(new WorkingIndexSnapshot(generations));
    }

    @Override
    public boolean isDurable() {
        return boundary != null && mutations.isDurable(boundary);
    }

    @Override
    public boolean awaitDurable(long deadlineNanos) throws IOException {
        return boundary != null && mutations.awaitDurable(boundary, deadlineNanos);
    }
}
