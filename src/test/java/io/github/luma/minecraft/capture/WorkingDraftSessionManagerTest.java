package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkingDraftSessionManagerTest {

    @TempDir
    Path tempDir;

    private static final Instant NOW = Instant.parse("2026-04-21T09:00:00Z");

    @Test
    void rebaseActiveWorkingDraftKeepsCapturedDelta() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("active.mbp"));
        BuildProject project = project();
        TrackedProject trackedProject = trackedProject(layout, project);
        WorkingDraftSessionManager manager = new WorkingDraftSessionManager();

        TrackedChangeBuffer buffer = manager.getOrCreate(trackedProject, WorldMutationSource.PLAYER, NOW);
        buffer.addChange(change("minecraft:stone", "minecraft:gold_block"), NOW.plusSeconds(1));

        manager.rebaseBaseVersion(trackedProject, "v0001", "v0002", NOW.plusSeconds(2));

        RecoveryDraft draft = manager.snapshotDraft(trackedProject).orElseThrow();
        assertEquals("v0002", draft.baseVersionId());
        assertEquals(1, draft.changes().size());
        assertEquals("minecraft:stone", draft.changes().getFirst().oldValue().blockId());
        assertEquals("minecraft:gold_block", draft.changes().getFirst().newValue().blockId());
    }

    @Test
    void rebasePersistedWorkingDraftWithoutSchemaChange() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("persisted.mbp"));
        BuildProject project = project();
        TrackedProject trackedProject = trackedProject(layout, project);
        RecoveryRepository repository = new RecoveryRepository();
        repository.saveDraft(layout, new RecoveryDraft(
                project.id().toString(),
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(change("minecraft:dirt", "minecraft:glass"))
        ));
        WorkingDraftSessionManager manager = new WorkingDraftSessionManager();

        manager.rebaseBaseVersion(trackedProject, "v0001", "v0002", NOW.plusSeconds(1));

        RecoveryDraft draft = repository.loadDraft(layout).orElseThrow();
        assertEquals("v0002", draft.baseVersionId());
        assertEquals(1, draft.changes().size());
        assertTrue(draft.entityChanges().isEmpty());
    }

    @Test
    void consumeDoesNotWaitForPendingBaselineWrites() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("consume.mbp"));
        BuildProject project = project();
        TrackedProject trackedProject = trackedProject(layout, project);
        ExecutorService draftExecutor = Executors.newSingleThreadExecutor();
        ExecutorService baselineExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch releaseBaseline = blockBaselineExecutor(baselineExecutor);
        CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                draftExecutor,
                baselineExecutor
        );
        try (coordinator) {
            WorkingDraftSessionManager manager = new WorkingDraftSessionManager(coordinator);
            TrackedChangeBuffer buffer = manager.getOrCreate(trackedProject, WorldMutationSource.PLAYER, NOW);
            buffer.addChange(change("minecraft:stone", "minecraft:gold_block"), NOW.plusSeconds(1));
            coordinator.enqueueBaselineWrite(layout, project.id().toString(), project.name(), chunkSnapshot(), NOW);

            CompletableFuture<Optional<TrackedChangeBuffer>> consumed = CompletableFuture.supplyAsync(() -> {
                try {
                    return manager.consumeAfterReconciliation(project.id().toString(), trackedProject);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });

            assertTrue(consumed.get(1, TimeUnit.SECONDS).isPresent());
            assertTrue(coordinator.hasPendingBaselineWrite(project.id().toString(), chunkSnapshot().chunk()));
        } finally {
            releaseBaseline.countDown();
            draftExecutor.shutdownNow();
            baselineExecutor.shutdownNow();
        }
    }

    @Test
    void idleFreezeDoesNotWaitForPendingBaselineWrites() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("idle-freeze.mbp"));
        BuildProject project = project();
        TrackedProject trackedProject = trackedProject(layout, project);
        ExecutorService draftExecutor = Executors.newSingleThreadExecutor();
        ExecutorService baselineExecutor = Executors.newSingleThreadExecutor();
        CountDownLatch releaseBaseline = blockBaselineExecutor(baselineExecutor);
        CapturePersistenceCoordinator coordinator = new CapturePersistenceCoordinator(
                new RecoveryRepository(),
                new BaselineChunkRepository(),
                draftExecutor,
                baselineExecutor
        );
        try (coordinator) {
            WorkingDraftSessionManager manager = new WorkingDraftSessionManager(coordinator);
            TrackedChangeBuffer buffer = manager.getOrCreate(trackedProject, WorldMutationSource.PLAYER, NOW);
            buffer.addChange(change("minecraft:stone", "minecraft:gold_block"), NOW.plusSeconds(1));
            coordinator.enqueueBaselineWrite(layout, project.id().toString(), project.name(), chunkSnapshot(), NOW);

            CompletableFuture<Optional<TrackedChangeBuffer>> frozen = CompletableFuture.supplyAsync(() -> {
                try {
                    return manager.freezeIdleAfterReconciliation(project.id().toString(), trackedProject);
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });

            assertTrue(frozen.get(1, TimeUnit.SECONDS).isPresent());
            assertTrue(coordinator.hasPendingBaselineWrite(project.id().toString(), chunkSnapshot().chunk()));
        } finally {
            releaseBaseline.countDown();
            draftExecutor.shutdownNow();
            baselineExecutor.shutdownNow();
        }
    }

    private static TrackedProject trackedProject(ProjectLayout layout, BuildProject project) {
        return new TrackedProject(
                layout,
                project,
                List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, NOW))
        );
    }

    private static BuildProject project() {
        return new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                "Tower",
                "",
                "1.21.11",
                "fabric",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                "main",
                "main",
                NOW,
                NOW,
                ProjectSettings.defaults(),
                false,
                false
        );
    }

    private static CountDownLatch blockBaselineExecutor(ExecutorService baselineExecutor) throws InterruptedException {
        CountDownLatch baselineStarted = new CountDownLatch(1);
        CountDownLatch releaseBaseline = new CountDownLatch(1);
        baselineExecutor.submit(() -> {
            baselineStarted.countDown();
            releaseBaseline.await(5, TimeUnit.SECONDS);
            return null;
        });
        assertTrue(baselineStarted.await(1, TimeUnit.SECONDS));
        return releaseBaseline;
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

    private static StoredBlockChange change(String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(1, 64, 1),
                payload(oldBlock),
                payload(newBlock)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
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
}
