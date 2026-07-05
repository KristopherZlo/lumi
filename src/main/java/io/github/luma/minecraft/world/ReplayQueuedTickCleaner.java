package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class ReplayQueuedTickCleaner {

    private ReplayQueuedTickCleaner() {
    }

    static void clear(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }

        BoundingBox box = new BoundingBox(pos);
        level.clearBlockEvents(box);
        level.getBlockTicks().clearArea(box);
        level.getFluidTicks().clearArea(box);
    }
}
