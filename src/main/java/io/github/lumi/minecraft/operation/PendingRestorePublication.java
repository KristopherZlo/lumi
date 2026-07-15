package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import java.util.Objects;

/** Publishes an applied partial Restore as ordinary pending world work. */
public final class PendingRestorePublication implements RestorePublication {
    private final MutationDurabilityTracker mutations;

    public PendingRestorePublication(MutationDurabilityTracker mutations) {
        this.mutations = Objects.requireNonNull(mutations, "mutations");
    }

    @Override
    public void publish(PreparedRestore restore) {
        restore.sections().forEach((key, ignored) -> mutations.registerSectionMutation(
                key, () -> restore.returnSections().get(key)));
        restore.entities().forEach((key, ignored) -> mutations.registerEntityMutation(
                key, () -> restore.returnEntities().get(key)));
    }
}
