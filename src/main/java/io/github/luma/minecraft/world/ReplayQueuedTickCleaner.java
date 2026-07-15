package io.github.luma.minecraft.world;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

final class ReplayQueuedTickCleaner {

    private ReplayQueuedTickCleaner() {
    }

    static void clear(ServerLevel level, BlockPos pos) {
        clear(level, pos == null ? List.of() : List.of(pos));
    }

    static void clear(ServerLevel level, Collection<BlockPos> positions) {
        if (level == null || positions == null || positions.isEmpty()) {
            return;
        }

        List<BlockPos> ordered = new ArrayList<>(positions.size());
        for (BlockPos pos : positions) {
            if (pos != null) {
                ordered.add(pos.immutable());
            }
        }
        ordered.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY())
                .thenComparingInt(pos -> pos.getZ())
                .thenComparingInt(pos -> pos.getX()));
        if (ordered.isEmpty()) {
            return;
        }

        BlockPos first = ordered.getFirst();
        int runStartX = first.getX();
        int runEndX = first.getX();
        int runY = first.getY();
        int runZ = first.getZ();
        for (int index = 1; index < ordered.size(); index++) {
            BlockPos pos = ordered.get(index);
            if (pos.getY() == runY && pos.getZ() == runZ && pos.getX() <= runEndX + 1) {
                runEndX = Math.max(runEndX, pos.getX());
                continue;
            }
            clear(level, new BoundingBox(runStartX, runY, runZ, runEndX, runY, runZ));
            runStartX = pos.getX();
            runEndX = pos.getX();
            runY = pos.getY();
            runZ = pos.getZ();
        }
        clear(level, new BoundingBox(runStartX, runY, runZ, runEndX, runY, runZ));
    }

    private static void clear(ServerLevel level, BoundingBox box) {
        level.clearBlockEvents(box);
        level.getBlockTicks().clearArea(box);
        level.getFluidTicks().clearArea(box);
    }
}
