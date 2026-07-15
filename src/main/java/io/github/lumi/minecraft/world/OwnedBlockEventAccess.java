package io.github.lumi.minecraft.world;

import net.minecraft.world.level.BlockEventData;

/** Mixin access for exact vanilla block-event ownership and cancellation. */
public interface OwnedBlockEventAccess {
    boolean lumi$hasBlockEvent(BlockEventData event);

    void lumi$removeBlockEvent(BlockEventData event);
}
