package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.minecraft.world.MutationDurabilityTracker;
import io.github.lumi.storage.repository.BranchRefRepository;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Moves the restored branch ref after verified ordinary Restore. */
public final class BranchRefRestorePublication implements RestorePublication {
    private final BranchRefRepository refs;
    private final Optional<WorkingIndexClearPublication> working;

    public BranchRefRestorePublication(BranchRefRepository refs) {
        this(refs, Optional.empty());
    }

    public BranchRefRestorePublication(
            BranchRefRepository refs,
            MutationDurabilityTracker mutations,
            WorkingIndexSnapshot captured) {
        this(refs, clearPublication(mutations, captured));
    }

    private BranchRefRestorePublication(
            BranchRefRepository refs,
            Optional<WorkingIndexClearPublication> working) {
        this.refs = Objects.requireNonNull(refs, "refs");
        this.working = Objects.requireNonNull(working, "working");
    }

    private static Optional<WorkingIndexClearPublication> clearPublication(
            MutationDurabilityTracker mutations,
            WorkingIndexSnapshot captured) {
        Objects.requireNonNull(mutations, "mutations");
        Objects.requireNonNull(captured, "captured");
        return captured.generations().isEmpty()
                ? Optional.empty()
                : Optional.of(new WorkingIndexClearPublication(mutations, captured));
    }

    @Override
    public void publish(PreparedRestore restore) throws IOException {
        refs.compareAndSet(restore.expectedRef(), restore.targetCommit());
        working.ifPresent(clear -> clear.publish(restore));
    }

    @Override
    public boolean isDurable() {
        return working.map(WorkingIndexClearPublication::isDurable).orElse(true);
    }
}
