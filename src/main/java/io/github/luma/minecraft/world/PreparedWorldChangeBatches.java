package io.github.luma.minecraft.world;

import java.util.List;

/**
 * Prepared apply work plus mechanism context discovered during the same
 * off-thread decode pass.
 */
public record PreparedWorldChangeBatches(
        List<PreparedChunkBatch> batches,
        MechanismReplayScope mechanismReplayScope
) {

    public PreparedWorldChangeBatches {
        batches = batches == null ? List.of() : List.copyOf(batches);
        mechanismReplayScope = mechanismReplayScope == null ? MechanismReplayScope.empty() : mechanismReplayScope;
    }
}
