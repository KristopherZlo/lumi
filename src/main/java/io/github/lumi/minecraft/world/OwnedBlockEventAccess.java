package io.github.lumi.minecraft.world;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockEventData;

/** Mixin access for vanilla block-event ownership and invalidation. */
public interface OwnedBlockEventAccess {
    boolean lumi$hasBlockEvent(BlockEventData event);

    void lumi$removeBlockEvent(BlockEventData event);

    void lumi$removeBlockEventsWhere(Predicate<BlockPos> matches);
}
