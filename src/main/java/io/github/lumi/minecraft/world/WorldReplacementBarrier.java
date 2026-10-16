package io.github.lumi.minecraft.world;

import java.io.IOException;

/** Quiesces external editors before replacement and resumes them after durable release. */
public interface WorldReplacementBarrier {
    WorldReplacementBarrier NONE = new WorldReplacementBarrier() {
        @Override public boolean awaitReady() { return true; }
        @Override public boolean isReleaseReady() { return true; }
        @Override public void released() { }
    };

    boolean awaitReady() throws IOException;

    boolean isReleaseReady();

    void released();
}
