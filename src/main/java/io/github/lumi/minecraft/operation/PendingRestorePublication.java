package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Publishes an applied partial Restore as ordinary pending world work. */
public final class PendingRestorePublication implements RestorePublication {
    private final MutationDurabilityTracker mutations;
    private WorkingIndexSnapshot boundary;

    public PendingRestorePublication(MutationDurabilityTracker mutations) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
    }

    @Override
    public void publish(PreparedRestore restore) {
        if (boundary != null) {
            throw new IllegalStateException("Pending Restore was already published");
        }
        Map<HistoryKey, Long> generations = new HashMap<>();
        restore.sections().forEach((key, ignored) -> generations.put(key,
                mutations.registerSectionMutation(
                        key, () -> restore.returnSections().get(key))));
        restore.entities().forEach((key, ignored) -> generations.put(key,
                mutations.registerEntityMutation(
                        key, () -> restore.returnEntities().get(key))));
        boundary = new WorkingIndexSnapshot(generations);
    }

    @Override
    public boolean isDurable() {
        return boundary != null && mutations.isDurable(boundary);
    }
}
