package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;

/**
 * Selects live undo/redo action identity for captured block mutations.
 */
final class UndoRedoActionGroupingPolicy {

    String actionIdForBlockChange(
            WorldMutationSource source,
            String actionId,
            StoredBlockChange change
    ) {
        return actionId;
    }
}
