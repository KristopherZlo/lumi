package io.github.lumi.minecraft.world;

import io.github.lumi.mixin.IOWorkerPersistenceAccessor;
import io.github.lumi.mixin.RegionFileStoragePersistenceAccessor;
import io.github.lumi.mixin.SimpleRegionStoragePersistenceAccessor;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** Forces only region files touched by a durable world mutation. */
final class MinecraftRegionStorageSynchronizer {
    private MinecraftRegionStorageSynchronizer() { }

    static CompletableFuture<Void> synchronize(
            SimpleRegionStorage storage,
            Collection<ChunkCoordinate> affectedChunks) {
        Set<ChunkCoordinate> regions = regionFiles(affectedChunks);
        if (regions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        var worker = ((SimpleRegionStoragePersistenceAccessor) storage).lumi$worker();
        var access = (IOWorkerPersistenceAccessor) worker;
        return storage.synchronize(false).thenCompose(ignored ->
                access.lumi$submitTask(() -> force(access.lumi$storage(), regions)));
    }

    static Set<ChunkCoordinate> regionFiles(
            Collection<ChunkCoordinate> affectedChunks) {
        Set<ChunkCoordinate> regions = new HashSet<>();
        affectedChunks.forEach(chunk -> regions.add(
                new ChunkCoordinate(
                        Math.floorDiv(chunk.x(), ChunkPos.REGION_SIZE)
                                * ChunkPos.REGION_SIZE,
                        Math.floorDiv(chunk.z(), ChunkPos.REGION_SIZE)
                                * ChunkPos.REGION_SIZE)));
        return Set.copyOf(regions);
    }

    private static Void force(
            RegionFileStorage storage, Set<ChunkCoordinate> regions) {
        try {
            var access = (RegionFileStoragePersistenceAccessor) (Object) storage;
            for (ChunkCoordinate region : regions) {
                var file = access.lumi$regionCache().get(ChunkPos.asLong(
                        region.x() / ChunkPos.REGION_SIZE,
                        region.z() / ChunkPos.REGION_SIZE));
                // Vanilla forces an evicted RegionFile while closing it.
                if (file != null) {
                    file.flush();
                }
            }
            return null;
        } catch (IOException failed) {
            throw new CompletionException(failed);
        }
    }
}
