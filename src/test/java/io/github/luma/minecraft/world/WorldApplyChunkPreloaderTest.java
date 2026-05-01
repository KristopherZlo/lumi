package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldApplyChunkPreloaderTest {

    @Test
    void collectsUniqueChunksForFastProfilesOnly() {
        LocalQueue queue = LocalQueue.completed(List.of(
                batch(1, 2),
                batch(1, 2),
                batch(3, 4)
        ));

        WorldApplyChunkPreloader normal = WorldApplyChunkPreloader.create(queue, WorldApplyProfile.NORMAL);
        WorldApplyChunkPreloader fast = WorldApplyChunkPreloader.create(queue, WorldApplyProfile.HISTORY_FAST);

        Assertions.assertFalse(normal.required());
        Assertions.assertEquals(2, fast.totalChunks());
    }

    @Test
    void advancesWithBudgetAndReleasesAcquiredTicketsOnce() {
        LocalQueue queue = LocalQueue.completed(List.of(
                batch(1, 2),
                batch(3, 4),
                batch(5, 6)
        ));
        WorldApplyChunkPreloader preloader = WorldApplyChunkPreloader.create(queue, WorldApplyProfile.HISTORY_FAST);
        FakeChunkPreloadAccess access = new FakeChunkPreloadAccess();
        access.loaded.add(new ChunkPoint(1, 2));
        WorldApplyBudget budget = new WorldApplyBudget(512, 1_000_000L, 1, 512, 1, 1, 512, 128, 2);

        WorldApplyChunkPreloader.PreloadTickResult first = preloader.advance(access, budget, Long.MAX_VALUE);

        Assertions.assertEquals(2, first.completedChunks());
        Assertions.assertEquals(1, first.alreadyLoadedChunks());
        Assertions.assertEquals(1, first.newlyLoadedChunks());
        Assertions.assertFalse(first.complete());
        Assertions.assertEquals(Set.of(new ChunkPoint(1, 2), new ChunkPoint(3, 4)), access.acquired);

        preloader.release(access);
        preloader.release(access);

        Assertions.assertEquals(Set.of(new ChunkPoint(1, 2), new ChunkPoint(3, 4)), access.released);
    }

    private static ChunkBatch batch(int x, int z) {
        return new ChunkBatch(
                new ChunkPoint(x, z),
                Map.of(),
                Map.of(),
                EntityBatch.empty(),
                BatchState.COMPLETE
        );
    }

    private static final class FakeChunkPreloadAccess implements ChunkPreloadAccess {

        private final Set<ChunkPoint> loaded = new LinkedHashSet<>();
        private final Set<ChunkPoint> acquired = new LinkedHashSet<>();
        private final Set<ChunkPoint> released = new LinkedHashSet<>();

        @Override
        public boolean isLoaded(ChunkPoint chunk) {
            return this.loaded.contains(chunk);
        }

        @Override
        public boolean load(ChunkPoint chunk) {
            this.loaded.add(chunk);
            return true;
        }

        @Override
        public void acquireTicket(ChunkPoint chunk) {
            this.acquired.add(chunk);
        }

        @Override
        public void releaseTicket(ChunkPoint chunk) {
            this.released.add(chunk);
        }
    }
}
