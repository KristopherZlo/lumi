package io.github.luma.gbreak.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToLongFunction;
import net.minecraft.util.math.BlockPos;

final class CorruptionRestoreWavePlanner {

    <T> List<T> orderFromCenter(Collection<T> entries, ToLongFunction<T> distanceSquared) {
        List<T> ordered = new ArrayList<>(entries);
        ordered.sort(Comparator.comparingLong(distanceSquared));
        return ordered;
    }

    long distanceSquared(BlockPos center, BlockPos pos) {
        long dx = pos.getX() - center.getX();
        long dy = pos.getY() - center.getY();
        long dz = pos.getZ() - center.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
