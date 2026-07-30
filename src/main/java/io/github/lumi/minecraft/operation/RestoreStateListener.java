package io.github.lumi.minecraft.operation;

import io.github.lumi.minecraft.world.WorldStateApply;
import java.io.IOException;

/** Reconciles runtime-only caches with the verified state before Restore releases its freeze. */
public interface RestoreStateListener {
    RestoreStateListener NONE = new RestoreStateListener() {
        @Override public void restored(WorldStateApply.State state) { }
        @Override public void returned(WorldStateApply.State state) { }
    };

    void restored(WorldStateApply.State state) throws IOException;

    void returned(WorldStateApply.State state) throws IOException;
}
