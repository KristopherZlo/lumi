package io.github.lumi.minecraft.world;

import io.github.lumi.LumiMod;
import io.github.lumi.mixin.IOWorkerPersistenceAccessor;
import io.github.lumi.mixin.RegionFileStoragePersistenceAccessor;
import io.github.lumi.mixin.SimpleRegionStoragePersistenceAccessor;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;

/** Forces only region files touched by a durable world mutation. */
final class MinecraftRegionStorageSynchronizer {
    private MinecraftRegionStorageSynchronizer() { }

    static CompletableFuture<Void> synchronize(
            SimpleRegionStorage storage,
            Collection<ChunkCoordinate> affectedChunks) {
        return prepare(storage, affectedChunks).complete();
    }

    static Synchronization prepare(
            SimpleRegionStorage storage,
            Collection<ChunkCoordinate> affectedChunks) {
        Set<ChunkCoordinate> regions = regionFiles(affectedChunks);
        if (regions.isEmpty()) {
            return new Synchronization(
                    CompletableFuture.completedFuture(null),
                    () -> CompletableFuture.completedFuture(null));
        }
        try {
            var worker = ((SimpleRegionStoragePersistenceAccessor) storage)
                    .lumi$worker();
            var access = (IOWorkerPersistenceAccessor) worker;
            var regionAccess = (RegionFileStoragePersistenceAccessor) (Object)
                    access.lumi$storage();
            return new Synchronization(
                    storage.synchronize(false),
                    () -> access.lumi$submitTask(
                            () -> force(regionAccess, regions)));
        } catch (ClassCastException | LinkageError unavailable) {
            LumiMod.LOGGER.warn(
                    "Scoped Restore force is unavailable; using global storage flush",
                    unavailable);
            return new Synchronization(
                    storage.synchronize(true),
                    () -> CompletableFuture.completedFuture(null));
        }
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
            RegionFileStoragePersistenceAccessor access,
            Set<ChunkCoordinate> regions) {
        try {
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

    static final class Synchronization {
        private final CompletableFuture<Void> writeBarrier;
        private final Supplier<CompletableFuture<Void>> force;
        private CompletableFuture<Void> forcing;

        private Synchronization(
                CompletableFuture<Void> writeBarrier,
                Supplier<CompletableFuture<Void>> force) {
            this.writeBarrier = writeBarrier;
            this.force = force;
        }

        CompletableFuture<Void> writeBarrier() {
            return writeBarrier;
        }

        CompletableFuture<Void> forceAffected() {
            if (forcing == null) {
                forcing = force.get();
            }
            return forcing;
        }

        CompletableFuture<Void> complete() {
            return writeBarrier.thenCompose(ignored -> forceAffected());
        }
    }
}
