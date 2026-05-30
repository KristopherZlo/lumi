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
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.world.EntityBatch;
import io.github.luma.minecraft.world.LumiSectionBuffer;
import io.github.luma.minecraft.world.MechanismReplayScope;
import io.github.luma.minecraft.world.PreparedBlockPlacement;
import io.github.luma.minecraft.world.PreparedChunkBatch;
import io.github.luma.minecraft.world.PreparedSectionApplyBatch;
import io.github.luma.minecraft.world.SectionApplyPath;
import io.github.luma.minecraft.world.SectionApplySafetyProfile;
import io.github.luma.domain.model.SectionChangeMask;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotWriter;
import java.nio.file.Files;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        DirectRestorePatchPlan plan = service.directRestorePatchPlan(
                project("main"),
                versions,
                variants,
                versions.get(3)
        );

        assertNotNull(plan);
        assertEquals(List.of("v0002"), plan.reverseVersions().stream().map(ProjectVersion::id).toList());
        assertEquals(List.of("v0003", "v0004"), plan.forwardVersions().stream().map(ProjectVersion::id).toList());
        assertNull(service.directRestorePatchVersions(project("main"), versions, variants, versions.get(3)));
        assertNull(service.applicableDirectRestorePatchPlan(project("main"), versions, variants, versions.get(3)));
    }

    @Test
    void exactInitialStateIsAppendedForDirectRollbackToInitial() {
        RestoreService service = new RestoreService();
        ProjectVersion initial = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        ProjectVersion head = version("v0002", "main", "v0001");
        DirectRestorePatchPlan plan = new DirectRestorePatchPlan(List.of(head), List.of());

        assertTrue(service.shouldAppendExactRootState(initial, null, plan));
    }

    @Test
    void exactWorldRootStateIsAppendedForDirectRollbackToWorldRoot() {
        RestoreService service = new RestoreService();
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion head = version("v0002", "main", "v0001");
        DirectRestorePatchPlan plan = new DirectRestorePatchPlan(List.of(head), List.of());

        assertTrue(service.shouldAppendExactRootState(root, null, plan));
    }

    @Test
    void exactRootStateIsSkippedForCleanNoOpInitialRestore() {
        RestoreService service = new RestoreService();
        ProjectVersion initial = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);

        assertFalse(service.shouldAppendExactRootState(initial, null, new DirectRestorePatchPlan(List.of(), List.of())));
    }

    @Test
    void exactInitialStatePlanUsesOnlyReplayAndPendingChunks(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ProjectVersion initial = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        ProjectVersion head = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        this.savePatchMetadata(
                layout,
                "patch-0002",
                "v0002",
                List.of(new ChunkPoint(0, 0), new ChunkPoint(3, 1))
        );

        RestoreService.ExactRootStateRestorePlan plan = service.exactRootStateRestorePlan(
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
    void exactWorldRootStatePlanUsesOnlyAffectedBaselineChunks(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion head = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        this.savePatchMetadata(
                layout,
                "patch-0002",
                "v0002",
                List.of(new ChunkPoint(0, 0), new ChunkPoint(3, 1))
        );
        createBaselineFile(layout, new ChunkPoint(0, 0));
        createBaselineFile(layout, new ChunkPoint(3, 1));
        createBaselineFile(layout, new ChunkPoint(7, 2));
        createBaselineFile(layout, new ChunkPoint(9, 9));

        RestoreService.ExactRootStateRestorePlan plan = service.exactRootStateRestorePlan(
                layout,
                root,
                draftInChunks(List.of(new ChunkPoint(7, 2))),
                new DirectRestorePatchPlan(List.of(head), List.of())
        );

        assertTrue(plan.append());
        assertEquals(List.of(new ChunkPoint(0, 0), new ChunkPoint(3, 1), new ChunkPoint(7, 2)), plan.chunks());
    }

    @Test
    void exactRootPositionCollectionIncludesNativeSectionCells() {
        RestoreService service = new RestoreService();
        LumiSectionBuffer buffer = LumiSectionBuffer.builder(4)
                .set(SectionChangeMask.localIndex(1, 2, 3), Blocks.STONE.defaultBlockState(), null)
                .set(SectionChangeMask.localIndex(2, 2, 3), Blocks.GOLD_BLOCK.defaultBlockState(), null)
                .build();
        PreparedSectionApplyBatch nativeSection = new PreparedSectionApplyBatch(
                new ChunkPoint(2, -1),
                4,
                buffer,
                new SectionApplySafetyProfile(SectionApplyPath.SECTION_NATIVE, "test"),
                false
        );
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(2, -1),
                List.of(new PreparedBlockPlacement(
                        new BlockPos(33, 66, -13),
                        Blocks.DIRT.defaultBlockState(),
                        null
                )),
                List.of(nativeSection),
                EntityBatch.empty()
        );

        List<BlockPoint> positions = service.blockPositions(List.of(batch));

        assertEquals(2, positions.size());
        assertEquals(new BlockPoint(33, 66, -13), positions.getFirst());
        assertTrue(positions.contains(new BlockPoint(34, 66, -13)));
    }

    @Test
    void exactRootReplayPositionsStaySparseWithoutMechanismScope() {
        RestoreMechanismReconciliationPlanner planner = new RestoreMechanismReconciliationPlanner();

        var positions = planner.boundedExactRootReplayPositions(
                project("main"),
                MechanismReplayScope.empty(),
                List.of(new BlockPoint(2, 64, 3)),
                null
        );

        assertTrue(positions.isPresent());
        assertEquals(List.of(new BlockPoint(2, 64, 3)), positions.orElseThrow());
    }

    @Test
    void exactRootReplayPositionsExpandMechanismSectionsInsideProjectBounds() {
        RestoreMechanismReconciliationPlanner planner = new RestoreMechanismReconciliationPlanner();
        MechanismReplayScope scope = new MechanismReplayScope(
                List.of(new BlockPoint(2, 64, 3)),
                List.of(new io.github.luma.domain.model.ChunkSectionPoint(0, 0, 4))
        );
        BuildProject project = BuildProject.create(
                "project",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(1, 64, 2), new BlockPoint(2, 65, 3)),
                new BlockPoint(1, 64, 2),
                NOW
        );

        var positions = planner.boundedExactRootReplayPositions(project, scope, List.of(), null).orElseThrow();

        assertEquals(8, positions.size());
        assertTrue(positions.contains(new BlockPoint(1, 64, 2)));
        assertTrue(positions.contains(new BlockPoint(2, 65, 3)));
        assertFalse(positions.contains(new BlockPoint(0, 64, 2)));
        assertFalse(positions.contains(new BlockPoint(2, 66, 3)));
    }

    @Test
    void exactRootReplayPositionsRejectLargeMechanismScope() {
        RestoreMechanismReconciliationPlanner planner = new RestoreMechanismReconciliationPlanner();
        List<io.github.luma.domain.model.ChunkSectionPoint> sections = java.util.stream.IntStream
                .range(0, 17)
                .mapToObj(index -> new io.github.luma.domain.model.ChunkSectionPoint(index, 0, 4))
                .toList();
        MechanismReplayScope scope = new MechanismReplayScope(List.of(), sections);

        var positions = planner.boundedExactRootReplayPositions(
                BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW),
                scope,
                List.of(),
                null
        );

        assertTrue(positions.isEmpty());
    }

    @Test
    void targetBlockStatesResolveSnapshotAndPatchChain(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        BlockPoint pos = new BlockPoint(1, 64, 1);
        this.snapshotWriter.writeFile(
                layout.snapshotFile("snapshot-0001"),
                snapshotWithState("minecraft:redstone_wire")
        );
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(new StoredBlockChange(
                        pos,
                        new StatePayload(state("minecraft:redstone_wire"), null),
                        StatePayload.air()
                )),
                List.of()
        ));
        ProjectVersion initial = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        ProjectVersion target = version("v0002", "main", "v0001", "", List.of("patch-0002"));

        var states = service.targetBlockStates(
                layout,
                project("main"),
                List.of(initial, target),
                target,
                List.of(pos)
        );

        assertEquals("minecraft:air", states.get(pos).blockId());
    }

    @Test
    void detectsMechanismPayloadsForTargetStatePartialRestoreFallback() {
        RestoreMechanismReconciliationPlanner planner = new RestoreMechanismReconciliationPlanner();

        assertTrue(planner.containsMechanismState(List.of(change(1, "minecraft:redstone_wire", "minecraft:air"))));
        assertTrue(planner.containsMechanismState(List.of(change(1, "minecraft:stone", "minecraft:comparator"))));
        assertFalse(planner.containsMechanismState(List.of(change(1, "minecraft:dirt", "minecraft:stone"))));
    }

    @Test
    void restoreTargetCanUseExplicitBranchWhenHeadVersionBelongsToMain() {
        RestoreRequestResolver resolver = new RestoreRequestResolver();
        ProjectVersion baseVersion = version("v0001", "main", "");
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0003", true, NOW),
                new ProjectVariant("feature", "feature", "v0001", "v0001", false, NOW)
        );

        ProjectVariant target = resolver.restoreTargetVariant(variants, baseVersion, "feature");

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
    void pendingEntityRollbackToDraftBaseSkipsSnapshotLookupForWorldRootLineage(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000070";
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion target = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0002",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity(entityId, 1.0D),
                        entity(entityId, 2.0D)
                ))
        );

        RecoveryDraft aligned = service.alignPendingEntityRollbackWithTarget(layout, List.of(root, target), target, draft);

        assertEquals(1.0D, x(aligned.entityChanges().getFirst().oldValue()));
        assertEquals(2.0D, x(aligned.entityChanges().getFirst().newValue()));
    }

    @Test
    void pendingEntityRollbackCanResolveTargetStateFromWorldRootPatchChain(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000071";
        EntityPayload targetEntity = entity(entityId, 1.0D);
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", null, targetEntity))
        ));
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion target = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0003",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity(entityId, 2.0D),
                        entity(entityId, 3.0D)
                ))
        );

        RecoveryDraft aligned = service.alignPendingEntityRollbackWithTarget(layout, List.of(root, target), target, draft);

        assertEquals(1.0D, x(aligned.entityChanges().getFirst().oldValue()));
        assertEquals(3.0D, x(aligned.entityChanges().getFirst().newValue()));
    }

    @Test
    void worldRootFallbackBaselineScopeIncludesOnlyDivergentRestorePathChunks(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ChunkPoint mainChunk = new ChunkPoint(2, 0);
        ChunkPoint branchChunk = new ChunkPoint(5, 1);
        this.savePatchMetadata(layout, "patch-0002", "v0002", List.of(mainChunk));
        this.savePatchMetadata(layout, "patch-0003", "v0003", List.of(branchChunk));
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW)
                .withActiveVariantId("feature", NOW);
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT),
                version("v0002", "main", "v0001", "", List.of("patch-0002")),
                version("v0003", "feature", "v0001", "", List.of("patch-0003"))
        );
        List<ProjectVariant> variants = List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, NOW),
                new ProjectVariant("feature", "feature", "v0001", "v0003", false, NOW)
        );

        DirectRestorePatchPlan plan = service.directRestorePatchPlan(project, versions, variants, versions.get(1));
        List<ChunkPoint> chunks = new WorldRootRestoreBaselineScope(
                new RestorePlanBuilder(),
                new RestoreChunkCollector(this.patchMetaRepository)
        ).resolve(
                layout,
                project,
                versions,
                versions.get(1),
                plan.allVersions()
        );

        assertEquals(2, chunks.size());
        assertTrue(chunks.containsAll(List.of(mainChunk, branchChunk)));
    }

    @Test
    void exactRootStatePlanFailsWhenAffectedWorldRootChunkHasNoBaseline(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ChunkPoint missingChunk = new ChunkPoint(2, 0);
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion head = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        this.savePatchMetadata(layout, "patch-0002", "v0002", List.of(missingChunk));
        RecoveryDraft pendingDraft = null;
        DirectRestorePatchPlan directPlan = new DirectRestorePatchPlan(List.of(head), List.of());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.exactRootStateRestorePlan(
                layout,
                root,
                pendingDraft,
                directPlan
        ));

        assertTrue(exception.getMessage().contains("Missing baseline chunks"));
        assertTrue(exception.getMessage().contains("2:0"));
    }

    @Test
    void initialRestoreSummaryUsesSnapshotChunksInsteadOfBaselineChunks(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), new SnapshotData(
                "project",
                NOW,
                64,
                79,
                List.of(new SnapshotChunkData(
                        3,
                        1,
                        List.of(new SnapshotSectionData(4, List.of(state("minecraft:air")), new short[4096])),
                        java.util.Map.of(),
                        List.of()
                ))
        ));
        createBaselineFile(layout, new ChunkPoint(9, 9));
        ProjectVersion initial = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);

        List<ChunkPoint> chunks = service.rootLikeSummaryChunks(layout, initial);

        assertEquals(List.of(new ChunkPoint(3, 1)), chunks);
    }

    @Test
    void targetStateResolverFailsWhenWorldRootPositionHasNoBaseline(@TempDir Path tempDir) {
        BlockTargetStateResolver resolver = new BlockTargetStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> resolver.resolve(
                layout,
                BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW),
                List.of(root),
                root,
                List.of(new BlockPoint(32, 64, 0))
        ));

        assertTrue(exception.getMessage().contains("Missing baseline chunks"));
        assertTrue(exception.getMessage().contains("2:0"));
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

    @Test
    void authoritativeEntityReplacementRemovesEntityMovedOutOfSelectedChunk(@TempDir Path tempDir) throws Exception {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000061";
        EntityPayload inSelectedChunk = entity(entityId, 1.0D);
        EntityPayload movedOut = entity(entityId, 32.0D);
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), snapshot(List.of(inSelectedChunk)));
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:block_display", inSelectedChunk, movedOut))
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
        return version(id, variantId, parentVersionId, snapshotId, patchIds, VersionKind.MANUAL);
    }

    private static ProjectVersion version(
            String id,
            String variantId,
            String parentVersionId,
            String snapshotId,
            List<String> patchIds,
            VersionKind versionKind
    ) {
        return new ProjectVersion(
                id,
                "project",
                variantId,
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

    private static SnapshotData snapshotWithState(String blockId) {
        short[] indexes = new short[4096];
        return new SnapshotData(
                "project",
                NOW,
                64,
                79,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(new SnapshotSectionData(4, List.of(state(blockId)), indexes)),
                        java.util.Map.of(),
                        List.of()
                ))
        );
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
                "v0002",
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

    private static double x(EntityPayload payload) {
        return payload.copyTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D);
    }
}
