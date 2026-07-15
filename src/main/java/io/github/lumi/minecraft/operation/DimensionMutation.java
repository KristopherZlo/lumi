package io.github.lumi.minecraft.operation;

import java.io.IOException;

/** A bounded, server-thread mutation owned by one dimension coordinator. */
public interface DimensionMutation {
    default boolean requiresFreeze() {
        return true;
    }

    void advance(long deadlineNanos) throws IOException;

    boolean isTerminal();

    boolean isSafeToRelease();
}
