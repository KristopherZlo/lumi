package io.github.luma.minecraft.world;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collection;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

final class HeightmapColumnUpdater {

    private static final MethodHandle SET_HEIGHT = resolveSetHeightHandle();

    int updateColumn(LevelChunk chunk, int localX, int worldY, int localZ, BlockState changedState) {
        if (chunk == null || changedState == null) {
            return 0;
        }
        if (SET_HEIGHT == null) {
            return this.updateColumnWithVanilla(chunk, localX, worldY, localZ, changedState);
        }

        Collection<Map.Entry<Heightmap.Types, Heightmap>> heightmaps = chunk.getHeightmaps();
        Heightmap[] pendingHeightmaps = new Heightmap[heightmaps.size()];
        @SuppressWarnings("unchecked")
        Predicate<BlockState>[] pendingPredicates = new Predicate[heightmaps.size()];
        int pendingCount = 0;
        int updatedHeightmaps = 0;
        for (Map.Entry<Heightmap.Types, Heightmap> entry : heightmaps) {
            Heightmap heightmap = entry.getValue();
            Predicate<BlockState> predicate = entry.getKey().isOpaque();
            int firstAvailable = heightmap.getFirstAvailable(localX, localZ);
            if (worldY <= firstAvailable - 2) {
                continue;
            }
            if (predicate.test(changedState)) {
                if (worldY >= firstAvailable && this.setHeight(heightmap, localX, localZ, worldY + 1)) {
                    updatedHeightmaps += 1;
                }
            } else if (firstAvailable - 1 == worldY) {
                pendingHeightmaps[pendingCount] = heightmap;
                pendingPredicates[pendingCount] = predicate;
                pendingCount += 1;
            }
        }

        if (pendingCount <= 0) {
            return updatedHeightmaps;
        }
        return updatedHeightmaps + this.scanColumnForPendingHeightmaps(
                chunk,
                localX,
                worldY,
                localZ,
                changedState,
                pendingHeightmaps,
                pendingPredicates,
                pendingCount
        );
    }

    private int scanColumnForPendingHeightmaps(
            LevelChunk chunk,
            int localX,
            int worldY,
            int localZ,
            BlockState changedState,
            Heightmap[] pendingHeightmaps,
            Predicate<BlockState>[] pendingPredicates,
            int pendingCount
    ) {
        int updatedHeightmaps = 0;
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int scanY = worldY - 1; scanY >= minY && pendingCount > 0; scanY--) {
            mutablePos.set(localX, scanY, localZ);
            BlockState state = chunk.getBlockState(mutablePos);
            int index = 0;
            while (index < pendingCount) {
                if (pendingPredicates[index].test(state)) {
                    if (this.setHeight(pendingHeightmaps[index], localX, localZ, scanY + 1)) {
                        updatedHeightmaps += 1;
                    } else {
                        pendingHeightmaps[index].update(localX, worldY, localZ, changedState);
                    }
                    pendingCount = this.removePending(pendingHeightmaps, pendingPredicates, pendingCount, index);
                } else {
                    index += 1;
                }
            }
        }

        for (int index = 0; index < pendingCount; index++) {
            if (this.setHeight(pendingHeightmaps[index], localX, localZ, minY)) {
                updatedHeightmaps += 1;
            } else {
                pendingHeightmaps[index].update(localX, worldY, localZ, changedState);
            }
        }
        return updatedHeightmaps;
    }

    private int updateColumnWithVanilla(LevelChunk chunk, int localX, int worldY, int localZ, BlockState changedState) {
        int updatedHeightmaps = 0;
        for (Map.Entry<Heightmap.Types, Heightmap> entry : chunk.getHeightmaps()) {
            if (entry.getValue().update(localX, worldY, localZ, changedState)) {
                updatedHeightmaps += 1;
            }
        }
        return updatedHeightmaps;
    }

    private int removePending(
            Heightmap[] pendingHeightmaps,
            Predicate<BlockState>[] pendingPredicates,
            int pendingCount,
            int removedIndex
    ) {
        int lastIndex = pendingCount - 1;
        pendingHeightmaps[removedIndex] = pendingHeightmaps[lastIndex];
        pendingPredicates[removedIndex] = pendingPredicates[lastIndex];
        pendingHeightmaps[lastIndex] = null;
        pendingPredicates[lastIndex] = null;
        return lastIndex;
    }

    private boolean setHeight(Heightmap heightmap, int localX, int localZ, int height) {
        try {
            SET_HEIGHT.invoke(heightmap, localX, localZ, height);
            return true;
        } catch (Throwable exception) {
            return false;
        }
    }

    private static MethodHandle resolveSetHeightHandle() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Heightmap.class, MethodHandles.lookup());
            return lookup.findVirtual(Heightmap.class, "setHeight", MethodType.methodType(void.class, int.class, int.class, int.class));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }
}
