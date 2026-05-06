package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.StatePayload;
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
        if (actionId == null || actionId.isBlank() || change == null) {
            return actionId;
        }
        if (source == WorldMutationSource.AXIOM && this.isSimplePlaceOrBreak(change)) {
            return actionId + ":block:" + this.positionKey(change.pos());
        }
        return actionId;
    }

    private boolean isSimplePlaceOrBreak(StoredBlockChange change) {
        boolean oldAir = this.isAir(change.oldValue());
        boolean newAir = this.isAir(change.newValue());
        return oldAir != newAir;
    }

    private boolean isAir(StatePayload payload) {
        return payload == null || "minecraft:air".equals(payload.blockId());
    }

    private String positionKey(BlockPoint pos) {
        if (pos == null) {
            return "unknown";
        }
        return pos.x() + "," + pos.y() + "," + pos.z();
    }
}
