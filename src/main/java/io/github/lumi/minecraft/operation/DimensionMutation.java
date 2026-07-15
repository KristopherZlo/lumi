package io.github.lumi.minecraft.operation;

import java.io.IOException;

/** A bounded, server-thread mutation owned by one dimension coordinator. */
public interface DimensionMutation {
    void advance(long deadlineNanos) throws IOException;

    boolean isTerminal();

    boolean isSafeToRelease();
}
