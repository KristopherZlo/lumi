package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RestoreServiceTest {

    private static final Instant NOW = Instant.parse("2026-04-28T00:00:00Z");
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void collapsePreparedBatchesKeepsOnlyLastPlacementPerBlock() {
        PreparedChunkBatch first = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(1, 64, 1), Blocks.STONE.defaultBlockState(), null),
                        new PreparedBlockPlacement(new BlockPos(2, 64, 2), Blocks.DIRT.defaultBlockState(), null)
                )
        );
        PreparedChunkBatch second = new PreparedChunkBatch(
                new ChunkPoint(0, 0),
                List.of(
                        new PreparedBlockPlacement(new BlockPos(1, 64, 1), Blocks.GOLD_BLOCK.defaultBlockState(), null)
                )
        );

        List<PreparedChunkBatch> collapsed = RestoreService.collapsePreparedBatches(List.of(first, second));

        assertEquals(1, collapsed.size());
        assertEquals(2, collapsed.getFirst().placements().size());
        assertEquals(new BlockPos(1, 64, 1), collapsed.getFirst().placements().getFirst().pos());
    }

    @Test
    void collapsePreparedBatchesKeepsEntityOnlyBatches() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:block_display");
        entity.putString("UUID", "00000000-0000-0000-0000-000000000050");
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(2, 3),
                List.of(),
                new EntityBatch(List.of(entity), List.of(), List.of())
        );

        List<PreparedChunkBatch> collapsed = RestoreService.collapsePreparedBatches(List.of(batch));

        assertEquals(1, collapsed.size());
        assertEquals(1, collapsed.getFirst().entityBatch().entitiesToSpawn().size());
    }

    @Test
    void directRestoreAcceptsSharedAncestorFromBranchBase() {
        RestoreService service = new RestoreService();
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", ""),
                version("v0002", "main", "v0001"),
                version("v0003", "main", "v0001"),
                version("v0004", "feature", "v0003")
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, NOW),
                new ProjectVariant("feature", "feature", "v0003", "v0004", false, NOW)
        );

        List<ProjectVersion> direct = service.directRestorePatchVersions(
                project("feature"),
                versions,
                variants,
                versions.get(2)
        );

        assertNotNull(direct);
        assertEquals(List.of("v0004"), direct.stream().map(ProjectVersion::id).toList());
    }

    @Test
    void directRestoreRejectsDetachedTargetOutsideActiveLineage() {
        RestoreService service = new RestoreService();
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", ""),
                version("v0002", "main", "v0001"),
                version("v0003", "main", "v0001"),
                version("v0004", "feature", "v0003")
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, NOW),
                new ProjectVariant("feature", "feature", "v0003", "v0004", false, NOW)
        );

        List<ProjectVersion> direct = service.directRestorePatchVersions(
                project("feature"),
                versions,
                variants,
                versions.get(1)
        );

        assertNull(direct);
    }

    @Test
    void directRestorePlanSupportsDivergentBranchHeadThroughCommonAncestor() {
        RestoreService service = new RestoreService();
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", ""),
                version("v0002", "main", "v0001"),
                version("v0003", "feature", "v0001"),
                version("v0004", "feature", "v0003")
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, NOW),
                new ProjectVariant("feature", "feature", "v0001", "v0004", false, NOW)
        );

        RestoreService.DirectRestorePatchPlan plan = service.directRestorePatchPlan(
                project("main"),
                versions,
                variants,
                versions.get(3)
        );

        assertNotNull(plan);
        assertEquals(List.of("v0002"), plan.reverseVersions().stream().map(ProjectVersion::id).toList());
        assertEquals(List.of("v0003", "v0004"), plan.forwardVersions().stream().map(ProjectVersion::id).toList());
        assertNull(service.directRestorePatchVersions(project("main"), versions, variants, versions.get(3)));
    }

    @Test
    void restoreTargetCanUseExplicitBranchWhenHeadVersionBelongsToMain() {
        RestoreService service = new RestoreService();
        ProjectVersion baseVersion = version("v0001", "main", "");
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, NOW),
                new ProjectVariant("feature", "feature", "v0001", "v0001", false, NOW)
        );

        ProjectVariant target = service.restoreTargetVariant(variants, baseVersion, "feature");

        assertEquals("feature", target.id());
    }

    @Test
    void quickRollbackUndoActionReappliesPreRestoreDraftState() {
        RestoreService service = new RestoreService();
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0002",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(change(1, "minecraft:stone", "minecraft:glass"))
        );

        RestoreService.RestoreUndoAction action = service.quickRollbackUndoAction(
                "project",
                "minecraft:overworld",
                "v0002",
                draft
        );

        assertNotNull(action);
        assertEquals("Lumi quick rollback", action.actor());
        assertEquals("project", action.projectId());
        assertEquals("minecraft:overworld", action.dimensionId());
        assertEquals("minecraft:glass", action.changes().getFirst().oldValue().blockId());
        assertEquals("minecraft:stone", action.changes().getFirst().newValue().blockId());
    }

    @Test
    void authoritativeEntityReplacementKeepsEmptyTargetChunkAuthoritative(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000060";
        EntityPayload entity = entity(entityId, 1.0D);
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), snapshot(List.of(entity)));
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", entity, null))
        ));
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", "snapshot-0001", List.of()),
                version("v0002", "main", "v0001", "", List.of("patch-0002"))
        );

        List<PreparedChunkBatch> batches = service.authoritativeEntityReplacementBatches(
                layout,
                versions,
                "v0002",
                List.of(new ChunkPoint(0, 0))
        );

        assertEquals(1, batches.size());
        assertEquals(new ChunkPoint(0, 0), batches.getFirst().chunk());
        assertEquals(true, batches.getFirst().entityBatch().replacePlacedEntities());
        assertEquals(0, batches.getFirst().entityBatch().entitiesToUpdate().size());
    }

    private static BuildProject project(String activeVariantId) {
        return BuildProject.create(
                        "project",
                        "minecraft:overworld",
                        new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(1, 1, 1)),
                        new BlockPoint(0, 0, 0),
                        NOW
                )
                .withActiveVariantId(activeVariantId, NOW);
    }

    private static ProjectVersion version(String id, String variantId, String parentVersionId) {
        return version(id, variantId, parentVersionId, "", List.of());
    }

    private static ProjectVersion version(
            String id,
            String variantId,
            String parentVersionId,
            String snapshotId,
            List<String> patchIds
    ) {
        return new ProjectVersion(
                id,
                "project",
                variantId,
                parentVersionId,
                snapshotId,
                patchIds,
                VersionKind.MANUAL,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
            NOW
        );
    }

    private static StoredBlockChange change(int x, String oldBlock, String newBlock) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 1),
                new StatePayload(state(oldBlock), null),
                new StatePayload(state(newBlock), null)
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static SnapshotData snapshot(List<EntityPayload> entities) {
        short[] indexes = new short[4096];
        return new SnapshotData(
                "project",
                NOW,
                64,
                64,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(new SnapshotSectionData(4, List.of(state("minecraft:air")), indexes)),
                        java.util.Map.of(),
                        entities
                ))
        );
    }

    private static EntityPayload entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(0.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
