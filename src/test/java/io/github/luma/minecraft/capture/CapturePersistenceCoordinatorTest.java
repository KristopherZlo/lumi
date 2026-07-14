package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.ChunkSectionPoint;
import io.github.luma.domain.model.ProjectDirtyScope;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.model.HistoryProtectionState;
import io.github.luma.domain.service.HistoryProtectionService;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.ProjectDirtyScopeRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapturePersistenceCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void deduplicatesPendingBaselineWritesPerChunk() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();
        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(),
                baselineChunkRepository,
                Executors.newSingleThreadExecutor()
        )) {
            ChunkSnapshotPayload snapshot = chunkSnapshot();

            assertTrue(coordinator.enqueueBaselineWrite(layout, "project", "Project", snapshot, Instant.parse("2026-04-21T09:00:00Z")));
            assertFalse(coordinator.enqueueBaselineWrite(layout, "project", "Project", snapshot, Instant.parse("2026-04-21T09:00:01Z")));

            coordinator.drainProject("project", "Project");
            assertEquals(List.of(snapshot.chunk()), baselineChunkRepository.listChunks(layout));
        }
    }

    @Test
    void drainsLatestRecoveryDraftFlush() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryRepository recoveryRepository = new RecoveryRepository();
        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                recoveryRepository,
                new BaselineChunkRepository(),
                Executors.newSingleThreadExecutor()
        )) {
            coordinator.enqueueDraftFlush(layout, "project", "Project", draft("minecraft:stone", Instant.parse("2026-04-21T09:00:00Z")));
            coordinator.enqueueDraftFlush(layout, "project", "Project", draft("minecraft:gold_block", Instant.parse("2026-04-21T09:00:01Z")));

            coordinator.drainProject("project", "Project");
            RecoveryDraft restored = recoveryRepository.loadDraft(layout).orElseThrow();
            assertEquals("minecraft:gold_block", restored.changes().getFirst().newValue().blockId());
        }
    }

    @Test
    void draftFlushCompletesWhileBaselineExecutorIsBlockedAndDrainWaitsForBoth() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryRepository recoveryRepository = new RecoveryRepository();
        ExecutorService draftExecutor = Executors.newSingleThreadExecutor();
        ExecutorService baselineExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch baselineStarted = new CountDownLatch(1);
        CountDownLatch releaseBaseline = new CountDownLatch(1);
        baselineExecutor.submit(() -> {
            baselineStarted.countDown();
            releaseBaseline.await(5, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(baselineStarted.await(1, TimeUnit.SECONDS));

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                recoveryRepository,
                new BaselineChunkRepository(),
                draftExecutor,
                baselineExecutor
        )) {
            coordinator.enqueueBaselineWrite(
                    layout,
                    "project",
                    "Project",
                    chunkSnapshot(),
                    Instant.parse("2026-04-21T09:00:00Z")
            );
            coordinator.enqueueDraftFlush(
                    layout,
                    "project",
                    "Project",
                    draft("minecraft:gold_block", Instant.parse("2026-04-21T09:00:01Z"))
            );

            RecoveryDraft flushed = waitForDraft(recoveryRepository, layout);
            assertEquals("minecraft:gold_block", flushed.changes().getFirst().newValue().blockId());

            CompletableFuture<Void> drained = CompletableFuture.runAsync(() -> {
                try {
                    coordinator.drainProject("project", "Project");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
            TimeUnit.MILLISECONDS.sleep(100);
            assertFalse(drained.isDone());

            releaseBaseline.countDown();
            drained.get(2, TimeUnit.SECONDS);
        } finally {
            releaseBaseline.countDown();
            stopExecutors(draftExecutor, baselineExecutor);
        }
    }

    @Test
    void draftOnlyDrainDoesNotWaitForBlockedBaselineWrites() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryRepository recoveryRepository = new RecoveryRepository();
        ExecutorService draftExecutor = Executors.newSingleThreadExecutor();
        ExecutorService baselineExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch baselineStarted = new CountDownLatch(1);
        CountDownLatch releaseBaseline = new CountDownLatch(1);
        baselineExecutor.submit(() -> {
            baselineStarted.countDown();
            releaseBaseline.await(5, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(baselineStarted.await(1, TimeUnit.SECONDS));

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                recoveryRepository,
                new BaselineChunkRepository(),
                draftExecutor,
                baselineExecutor
        )) {
            coordinator.enqueueBaselineWrite(layout, "project", "Project", chunkSnapshot(), Instant.parse("2026-04-21T09:00:00Z"));
            coordinator.enqueueDraftFlush(layout, "project", "Project", draft("minecraft:gold_block", Instant.parse("2026-04-21T09:00:01Z")));

            coordinator.drainDraftFlushes("project", "Project");

            RecoveryDraft restored = recoveryRepository.loadDraft(layout).orElseThrow();
            assertEquals("minecraft:gold_block", restored.changes().getFirst().newValue().blockId());
            assertTrue(coordinator.hasPendingBaselineWrite("project", chunkSnapshot().chunk()));
        } finally {
            releaseBaseline.countDown();
            stopExecutors(draftExecutor, baselineExecutor);
        }
    }

    @Test
    void deleteDraftDoesNotWaitForBlockedBaselineWrites() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        RecoveryRepository recoveryRepository = new RecoveryRepository();
        recoveryRepository.saveDraft(layout, draft("minecraft:gold_block", Instant.parse("2026-04-21T09:00:01Z")));
        ExecutorService draftExecutor = Executors.newSingleThreadExecutor();
        ExecutorService baselineExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch baselineStarted = new CountDownLatch(1);
        CountDownLatch releaseBaseline = new CountDownLatch(1);
        baselineExecutor.submit(() -> {
            baselineStarted.countDown();
            releaseBaseline.await(5, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(baselineStarted.await(1, TimeUnit.SECONDS));

        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                recoveryRepository,
                new BaselineChunkRepository(),
                draftExecutor,
                baselineExecutor
        )) {
            coordinator.enqueueBaselineWrite(layout, "project", "Project", chunkSnapshot(), Instant.parse("2026-04-21T09:00:00Z"));

            CompletableFuture<Void> deleted = CompletableFuture.runAsync(() -> {
                try {
                    coordinator.deleteDraft(layout, "project", "Project");
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });

            deleted.get(1, TimeUnit.SECONDS);
            assertTrue(recoveryRepository.loadDraft(layout).isEmpty());
            assertTrue(coordinator.hasPendingBaselineWrite("project", chunkSnapshot().chunk()));
        } finally {
            releaseBaseline.countDown();
            stopExecutors(draftExecutor, baselineExecutor);
        }
    }

    @Test
    void baselineWriterThreadCountUsesBoundedDefaultAndClampedOverride() {
        assertEquals(4, CapturePersistenceCoordinator.baselineWriterThreads(null));
        assertEquals(4, CapturePersistenceCoordinator.baselineWriterThreads(""));
        assertEquals(1, CapturePersistenceCoordinator.baselineWriterThreads("1"));
        assertEquals(8, CapturePersistenceCoordinator.baselineWriterThreads("64"));
        assertEquals(4, CapturePersistenceCoordinator.baselineWriterThreads("invalid"));
        assertEquals(4, CapturePersistenceCoordinator.baselineWriterThreads("0"));
    }

    @Test
    void dirtyScopePersistenceFailureMarksProjectDegraded() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("degraded.mbp"));
        java.nio.file.Files.createDirectories(layout.projectDirtyScopeFile());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                new ProjectDirtyScopeRepository(),
                executor,
                executor
        )) {
            ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "v1");
            scope.markBlockSection(new ChunkSectionPoint(1, 2, 3));
            coordinator.enqueueDirtyScopeFlush(layout, "project", "Project", scope);
            org.junit.jupiter.api.Assertions.assertThrows(
                    java.io.IOException.class,
                    () -> coordinator.drainDirtyScopeFlushes("project", "Project")
            );
        }

        assertEquals(
                HistoryProtectionState.DEGRADED,
                new HistoryProtectionService().load(layout, null).state()
        );
    }

    private static ChunkSnapshotPayload chunkSnapshot() {
        short[] indexes = new short[4096];
        indexes[0] = 1;
        return new ChunkSnapshotPayload(
                2,
                3,
                0,
                15,
                List.of(new ChunkSectionSnapshotPayload(
                        0,
                        List.of(payload("minecraft:air").stateTag(), payload("minecraft:stone").stateTag()),
                        packIndexes(indexes, 1),
                        1
                )),
                Map.of()
        );
    }

    private static RecoveryDraft draft(String blockId, Instant updatedAt) {
        return new RecoveryDraft(
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                updatedAt.minusSeconds(30),
                updatedAt,
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        payload("minecraft:stone"),
                        payload(blockId)
                ))
        );
    }

    private static RecoveryDraft waitForDraft(RecoveryRepository repository, ProjectLayout layout) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            var draft = repository.loadDraft(layout);
            if (draft.isPresent()) {
                return draft.get();
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("Timed out waiting for draft flush");
    }

    private static StatePayload payload(String blockId) {
        net.minecraft.nbt.CompoundTag state = new net.minecraft.nbt.CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }

    private static long[] packIndexes(short[] indexes, int bitsPerEntry) {
        int bitCount = indexes.length * bitsPerEntry;
        long[] packed = new long[(bitCount + 63) / 64];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int index = 0; index < indexes.length; index++) {
            long value = indexes[index] & mask;
            long bitIndex = (long) index * bitsPerEntry;
            int startLong = (int) (bitIndex >>> 6);
            int startOffset = (int) (bitIndex & 63L);
            packed[startLong] |= value << startOffset;
            int spill = startOffset + bitsPerEntry - 64;
            if (spill > 0) {
                packed[startLong + 1] |= value >>> (bitsPerEntry - spill);
            }
        }
        return packed;
    }

    private static void stopExecutors(ExecutorService... executors) throws InterruptedException {
        for (ExecutorService executor : executors) {
            executor.shutdownNow();
        }
        for (ExecutorService executor : executors) {
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void coalescesDirtyScopeFlushesOffThread() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        ProjectDirtyScope scope = ProjectDirtyScope.empty("project", "main", "v0001");
        ProjectDirtyScope stored = ProjectDirtyScope.empty("project", "main", "v0001");
        stored.markBlockSection(new ChunkSectionPoint(-1, -2, -3));
        new ProjectDirtyScopeRepository().save(layout, stored);
        try (CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                Executors.newSingleThreadExecutor()
        )) {
            scope.markBlockSection(new ChunkSectionPoint(1, 2, 3));
            coordinator.enqueueDirtyScopeFlush(layout, "project", "Project", scope);
            scope.markBlockSection(new ChunkSectionPoint(4, 5, 6));
            coordinator.enqueueDirtyScopeFlush(layout, "project", "Project", scope);

            coordinator.drainDirtyScopeFlushes("project", "Project");

            ProjectDirtyScope restored = new ProjectDirtyScopeRepository().load(layout).orElseThrow();
            assertTrue(restored.blockSections().containsAll(scope.blockSections()));
            assertTrue(restored.blockSections().containsAll(stored.blockSections()));
            assertFalse(coordinator.hasPendingDirtyScopeFlush("project"));
        }
    }
}
