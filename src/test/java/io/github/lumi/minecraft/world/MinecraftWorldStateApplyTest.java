package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.SectionKey;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MinecraftWorldStateApplyTest {
    @Test
    void ordersVisibleChunksFirstWithoutSplittingTheirSections() {
        SectionKey storedClosest = new SectionKey(1, 0, 1);
        SectionKey visibleFar = new SectionKey(20, 0, 20);
        SectionKey nearHigh = new SectionKey(2, 1, 1);
        SectionKey nearLow = new SectionKey(2, 0, 1);

        assertEquals(List.of(nearLow, nearHigh, visibleFar, storedClosest),
                MinecraftWorldStateApply.prioritize(
                        List.of(storedClosest, visibleFar, nearHigh, nearLow),
                        List.of(new ChunkCoordinate(0, 0)),
                        Set.of(
                                new ChunkCoordinate(2, 1),
                                new ChunkCoordinate(20, 20))));
    }

    @Test
    void groupsStoredChunksByRegionAndRegionFileIndex() {
        SectionKey nextRegion = new SectionKey(32, 0, 0);
        SectionKey lateRow = new SectionKey(0, 0, 31);
        SectionKey earlyRow = new SectionKey(31, 0, 0);

        assertEquals(List.of(earlyRow, lateRow, nextRegion),
                MinecraftWorldStateApply.prioritize(
                        List.of(nextRegion, lateRow, earlyRow),
                        List.of(new ChunkCoordinate(0, 0)), Set.of()));
    }

    @Test
    void groupsEntityChunksByNearestRegionAndRegionFileIndex() {
        EntityChunkKey earlyRow = new EntityChunkKey(31, 0);
        EntityChunkKey lateRow = new EntityChunkKey(0, 31);
        EntityChunkKey negativeRegionStart = new EntityChunkKey(-32, 0);
        EntityChunkKey negativeRegionEnd = new EntityChunkKey(-1, 0);
        EntityChunkKey nextRegion = new EntityChunkKey(32, 0);

        assertEquals(List.of(
                        earlyRow, lateRow,
                        negativeRegionStart, negativeRegionEnd, nextRegion),
                MinecraftWorldStateApply.prioritizeEntities(
                        List.of(
                                nextRegion, negativeRegionEnd, lateRow,
                                negativeRegionStart, earlyRow),
                        List.of(new ChunkCoordinate(0, 0))));
    }

    @Test
    void selectsEachAffectedRegionFileOnceAcrossSignedChunkCoordinates() {
        assertEquals(Set.of(
                        new ChunkCoordinate(-64, -32),
                        new ChunkCoordinate(-32, -32),
                        new ChunkCoordinate(0, 0),
                        new ChunkCoordinate(32, 0)),
                MinecraftRegionStorageSynchronizer.regionFiles(List.of(
                        new ChunkCoordinate(-33, -1),
                        new ChunkCoordinate(-32, -32),
                        new ChunkCoordinate(-1, -1),
                        new ChunkCoordinate(0, 0),
                        new ChunkCoordinate(31, 31),
                        new ChunkCoordinate(32, 0))));
    }

    @Test
    void forcesEachStorageAsSoonAsItsOwnWriteBarrierCompletes() {
        CompletableFuture<Void> barrier = new CompletableFuture<>();
        CompletableFuture<Void> force = new CompletableFuture<>();
        AtomicInteger forceCalls = new AtomicInteger();
        var synchronization = new MinecraftRegionStorageSynchronizer.Synchronization(
                barrier, () -> {
                    forceCalls.incrementAndGet();
                    return force;
                });

        CompletableFuture<Void> complete = synchronization.complete();
        assertEquals(0, forceCalls.get());
        barrier.complete(null);
        assertEquals(1, forceCalls.get());
        assertFalse(complete.isDone());
        force.complete(null);
        assertTrue(complete.isDone());
    }
}
