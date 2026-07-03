package io.github.luma.minecraft.capture;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Preserves the requested block transition when synchronous callbacks consume
 * the block before Level.setBlock returns.
 */
public final class PostCallbackBlockMutationPolicy {

    public List<HistoryCaptureManager.BlockChangeInput> changesAfterCallbacks(
            BlockPos pos,
            BlockState oldState,
            BlockState requestedState,
            BlockState appliedState,
            CompoundTag oldBlockEntity,
            CompoundTag appliedBlockEntity
    ) {
        if (pos == null || oldState == null || requestedState == null || appliedState == null) {
            return List.of();
        }

        List<HistoryCaptureManager.BlockChangeInput> changes = new ArrayList<>(2);
        if (!requestedState.equals(appliedState)) {
            changes.add(new HistoryCaptureManager.BlockChangeInput(
                    pos,
                    oldState,
                    requestedState,
                    oldBlockEntity,
                    null
            ));
        }
        changes.add(new HistoryCaptureManager.BlockChangeInput(
                pos,
                oldState,
                appliedState,
                oldBlockEntity,
                appliedBlockEntity
        ));
        return List.copyOf(changes);
    }
}
