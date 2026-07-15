package io.github.lumi.minecraft.operation;

import io.github.lumi.domain.service.PreparedRestore;

/** Reconciles runtime-only caches with the verified state before Restore releases its freeze. */
public interface RestoreStateListener {
    RestoreStateListener NONE = new RestoreStateListener() {
        @Override public void restored(PreparedRestore restore) { }
        @Override public void returned(PreparedRestore restore) { }
    };

    void restored(PreparedRestore restore);

    void returned(PreparedRestore restore);
}
