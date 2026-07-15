package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;
import io.github.lumi.storage.repository.BranchRefRepository;
import java.io.IOException;
import java.util.Objects;

/** Moves the restored branch ref after verified ordinary Restore. */
public final class BranchRefRestorePublication implements RestorePublication {
    private final BranchRefRepository refs;

    public BranchRefRestorePublication(BranchRefRepository refs) {
        this.refs = Objects.requireNonNull(refs, "refs");
    }

    @Override
    public void publish(PreparedRestore restore) throws IOException {
        refs.compareAndSet(restore.expectedRef(), restore.targetCommit());
    }
}
