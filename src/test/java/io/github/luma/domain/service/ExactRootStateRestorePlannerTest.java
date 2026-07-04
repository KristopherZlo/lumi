package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchMetaRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactRootStateRestorePlannerTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");

    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final ExactRootStateRestorePlanner planner = new ExactRootStateRestorePlanner(
            new RestoreChunkCollector(this.patchMetaRepository)
    );

    @Test
    void appendsExactInitialStateForDirectRollbackToInitial() {
        ProjectVersion initial = version("v0001", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        ProjectVersion head = version("v0002", "v0001", "", List.of(), VersionKind.MANUAL);
        DirectRestorePatchPlan plan = new DirectRestorePatchPlan(List.of(head), List.of());

        assertTrue(this.planner.shouldAppend(initial, null, plan));
    }

    @Test
    void appendsExactWorldRootStateForDirectRollbackToWorldRoot() {
        ProjectVersion root = version("v0001", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion head = version("v0002", "v0001", "", List.of(), VersionKind.MANUAL);
        DirectRestorePatchPlan plan = new DirectRestorePatchPlan(List.of(head), List.of());

        assertTrue(this.planner.shouldAppend(root, null, plan));
    }

    @Test
    void skipsExactRootStateForCleanNoOpInitialRestore() {
        ProjectVersion initial = version("v0001", "", "snapshot-0001", List.of(), VersionKind.INITIAL);

        assertFalse(this.planner.shouldAppend(initial, null, DirectRestorePatchPlan.empty()));
    }

    @Test
    void skipsExactWorldRootStateForPendingRollbackToDraftBase(@TempDir Path tempDir) throws Exception {
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ProjectVersion root = version("v0001", "", "", List.of(), VersionKind.WORLD_ROOT);

        ExactRootStateRestorePlan plan = this.planner.plan(
                layout,
                root,
                draftInChunks("v0001", List.of(new ChunkPoint(7, 2))),
                DirectRestorePatchPlan.empty()
        );

        assertFalse(plan.append());
        assertTrue(plan.chunks().isEmpty());
    }

    @Test
    void plansExactInitialStateForReplayAndPendingChunks(@TempDir Path tempDir) throws Exception {
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ProjectVersion initial = version("v0001", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        ProjectVersion head = version("v0002", "v0001", "", List.of("patch-0002"), VersionKind.MANUAL);
        this.savePatchMetadata(layout, "patch-0002", "v0002", List.of(new ChunkPoint(0, 0), new ChunkPoint(3, 1)));

        ExactRootStateRestorePlan plan = this.planner.plan(
                layout,
                initial,
                draftInChunks(List.of(new ChunkPoint(7, 2))),
                new DirectRestorePatchPlan(List.of(head), List.of())
        );

        assertTrue(plan.append());
        assertEquals(
                List.of(new ChunkPoint(0, 0), new ChunkPoint(3, 1), new ChunkPoint(7, 2)),
                plan.chunks()
        );
    }

    @Test
    void plansExactWorldRootStateForOnlyAffectedBaselineChunks(@TempDir Path tempDir) throws Exception {
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ProjectVersion root = version("v0001", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion head = version("v0002", "v0001", "", List.of("patch-0002"), VersionKind.MANUAL);
        this.savePatchMetadata(layout, "patch-0002", "v0002", List.of(new ChunkPoint(0, 0), new ChunkPoint(3, 1)));
        createBaselineFile(layout, new ChunkPoint(0, 0));
        createBaselineFile(layout, new ChunkPoint(3, 1));
        createBaselineFile(layout, new ChunkPoint(7, 2));
        createBaselineFile(layout, new ChunkPoint(9, 9));

        ExactRootStateRestorePlan plan = this.planner.plan(
                layout,
                root,
                draftInChunks(List.of(new ChunkPoint(7, 2))),
                new DirectRestorePatchPlan(List.of(head), List.of())
        );

        assertTrue(plan.append());
        assertEquals(List.of(new ChunkPoint(0, 0), new ChunkPoint(3, 1), new ChunkPoint(7, 2)), plan.chunks());
    }

    @Test
    void plansAffectedWorldRootChunksEvenWhenLegacyBaselineIsMissing(@TempDir Path tempDir) throws Exception {
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ChunkPoint missingChunk = new ChunkPoint(2, 0);
        ProjectVersion root = version("v0001", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion head = version("v0002", "v0001", "", List.of("patch-0002"), VersionKind.MANUAL);
        this.savePatchMetadata(layout, "patch-0002", "v0002", List.of(missingChunk));

        ExactRootStateRestorePlan plan = this.planner.plan(
                layout,
                root,
                null,
                new DirectRestorePatchPlan(List.of(head), List.of())
        );

        assertTrue(plan.append());
        assertEquals(List.of(missingChunk), plan.chunks());
    }

    private void savePatchMetadata(
            ProjectLayout layout,
            String patchId,
            String versionId,
            List<ChunkPoint> chunks
    ) throws Exception {
        this.patchMetaRepository.save(layout, new PatchMetadata(
                patchId,
                "project",
                versionId,
                patchId + ".bin.lz4",
                chunks.stream()
                        .map(chunk -> new PatchChunkSlice(
                                chunk.x(),
                                chunk.z(),
                                1,
                                0L,
                                0,
                                List.of(),
                                0
                        ))
                        .toList(),
                new PatchStats(chunks.size(), chunks.size())
        ));
    }

    private static RecoveryDraft draftInChunks(List<ChunkPoint> chunks) {
        return draftInChunks("v0002", chunks);
    }

    private static RecoveryDraft draftInChunks(String baseVersionId, List<ChunkPoint> chunks) {
        List<StoredBlockChange> changes = chunks.stream()
                .map(chunk -> new StoredBlockChange(
                        new BlockPoint(chunk.x() << 4, 64, chunk.z() << 4),
                        StatePayload.air(),
                        new StatePayload(state("minecraft:stone"), null)
                ))
                .toList();
        return new RecoveryDraft(
                "project",
                "main",
                baseVersionId,
                "tester",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                changes
        );
    }

    private static void createBaselineFile(ProjectLayout layout, ChunkPoint chunk) throws Exception {
        Path directory = layout.cacheDir().resolve("baseline-chunks");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("chunk_" + chunk.x() + "_" + chunk.z() + ".bin.lz4"), "");
    }

    private static ProjectVersion version(
            String id,
            String parentVersionId,
            String snapshotId,
            List<String> patchIds,
            VersionKind versionKind
    ) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentVersionId,
                snapshotId,
                patchIds,
                versionKind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
