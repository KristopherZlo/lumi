package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Removes both loaded and still-pending block entities from a chunk. */
final class BlockEntityRemover {

    void remove(ServerLevel level, BlockPos pos) {
        level.getBlockEntity(pos);
        level.removeBlockEntity(pos);
    }
}
