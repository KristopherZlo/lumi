package io.github.luma.minecraft.world;

import java.util.Objects;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Selects Minecraft block update flags for Lumi's internal world replay path.
 *
 * <p>Restore, undo, redo, and recovery apply persisted states directly. They
 * must refresh clients without re-running placement physics, redstone neighbor
 * updates, or block removal side effects from the replay itself.
 */
final class WorldApplyBlockUpdatePolicy {

    private static final int PERSISTENT_APPLY_FLAGS =
            Block.UPDATE_CLIENTS
                    | Block.UPDATE_KNOWN_SHAPE
                    | Block.UPDATE_SUPPRESS_DROPS
                    | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS
                    | Block.UPDATE_SKIP_ON_PLACE;

    int placementFlags(BlockState state) {
        Objects.requireNonNull(state, "state");
        return PERSISTENT_APPLY_FLAGS;
    }
}
