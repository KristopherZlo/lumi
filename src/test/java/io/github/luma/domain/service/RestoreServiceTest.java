package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.PatchChunkSlice;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.PatchStats;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.PartialRestoreRequest;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RestorePlanMode;
import io.github.luma.domain.model.RestoreEntityTypeSelection;
import io.github.luma.domain.model.SectionChangeMask;
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
import io.github.luma.minecraft.world.PreparedChunkBatchCollapser;
import io.github.luma.minecraft.world.PreparedWorldChangeBatches;
import io.github.luma.minecraft.world.PreparedSectionApplyBatch;
import io.github.luma.minecraft.world.SectionApplyPath;
import io.github.luma.minecraft.world.SectionApplySafetyProfile;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.minecraft.debug.PartialRestoreDiagnosticsLog;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotReader;
import io.github.luma.storage.repository.SnapshotWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
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
    void preparedBatchCollapserKeepsOnlyLastPlacementPerBlock() {
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

        List<PreparedChunkBatch> collapsed = new PreparedChunkBatchCollapser().collapse(List.of(first, second));

        assertEquals(1, collapsed.size());
        assertEquals(2, collapsed.getFirst().placements().size());
        assertEquals(new BlockPos(1, 64, 1), collapsed.getFirst().placements().getFirst().pos());
    }

    @Test
    void preparedBatchCollapserKeepsEntityOnlyBatches() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:block_display");
        entity.putString("UUID", "00000000-0000-0000-0000-000000000050");
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(2, 3),
                List.of(),
                new EntityBatch(List.of(entity), List.of(), List.of())
        );

        List<PreparedChunkBatch> collapsed = new PreparedChunkBatchCollapser().collapse(List.of(batch));

        assertEquals(1, collapsed.size());
        assertEquals(1, collapsed.getFirst().entityBatch().entitiesToSpawn().size());
    }

    @Test
    void preparedBatchCollapserKeepsEntityTypeExclusions() {
        CompoundTag entity = new CompoundTag();
        entity.putString("id", "minecraft:block_display");
        entity.putString("UUID", "00000000-0000-0000-0000-000000000051");
        PreparedChunkBatch batch = new PreparedChunkBatch(
                new ChunkPoint(2, 3),
                List.of(),
                EntityBatch.replaceEntities(List.of(entity), List.of("minecraft:tnt"))
        );

        List<PreparedChunkBatch> collapsed = new PreparedChunkBatchCollapser().collapse(List.of(batch));

        assertEquals(1, collapsed.size());
        assertEquals(true, collapsed.getFirst().entityBatch().replaceEntities());
        assertTrue(collapsed.getFirst().entityBatch().excludedEntityTypes().contains("minecraft:tnt"));
    }

    @Test
    void exactRootPositionCollectionIncludesNativeSectionCells() {
        RestoreChunkCollector collector = new RestoreChunkCollector(this.patchMetaRepository);
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

        List<BlockPoint> positions = collector.blockPositions(List.of(batch));

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
    void exactRootReplaySelectionKeepsChangedPositionsWhenMechanismScopeIsTooLarge() {
        RestoreMechanismReconciliationPlanner planner = new RestoreMechanismReconciliationPlanner();
        BlockPoint changedPosition = new BlockPoint(2, 64, 3);
        List<io.github.luma.domain.model.ChunkSectionPoint> sections = java.util.stream.IntStream
                .range(0, 17)
                .mapToObj(index -> new io.github.luma.domain.model.ChunkSectionPoint(index, 0, 4))
                .toList();
        MechanismReplayScope scope = new MechanismReplayScope(List.of(), sections);

        RestoreMechanismReplaySelection selection = planner.selectExactRootReplayPositions(
                BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW),
                scope,
                List.of(changedPosition),
                null
        );

        assertEquals(List.of(changedPosition), selection.positions());
        assertTrue(selection.truncatedMechanismScope());
    }

    @Test
    void restoreMechanismDetectionUsesSharedPropertyBasedPolicy() {
        RestoreMechanismReconciliationPlanner planner = new RestoreMechanismReconciliationPlanner();

        assertTrue(planner.containsMechanismState(List.of(change(
                1,
                state("minecraft:air"),
                state("minecraft:copper_bulb", "lit", "true")
        ))));
        assertFalse(planner.containsMechanismState(List.of(change(
                1,
                state("minecraft:dirt"),
                state("minecraft:stone")
        ))));
    }

    @Test
    void directForwardRestoreStaysSparseWhenMechanismTargetScopeIsTooLarge(@TempDir Path tempDir)
            throws Throwable {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        List<StoredBlockChange> changes = java.util.stream.IntStream.range(0, 17)
                .mapToObj(index -> new StoredBlockChange(
                        new BlockPoint(index << 4, 64, 0),
                        StatePayload.air(),
                        new StatePayload(state("minecraft:redstone_wire"), null)
                ))
                .toList();
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                changes,
                List.of()
        ));
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW)
                .withActiveVariantId("main", NOW);
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion target = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        ProjectVariant activeVariant = new ProjectVariant("main", "main", "v0001", "v0001", true, NOW);

        Optional<List<PreparedChunkBatch>> decoded = invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                List.of(root, target),
                List.of(activeVariant),
                target
        );

        assertTrue(decoded.isPresent());
    }

    @Test
    void directForwardRestoreSkipsMechanismTargetStateWhenBaselineChunkIsMissing(@TempDir Path tempDir)
            throws Throwable {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(new StoredBlockChange(
                        new BlockPoint(-64, 64, -48),
                        StatePayload.air(),
                        new StatePayload(state("minecraft:redstone_wire"), null)
                )),
                List.of()
        ));
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW)
                .withActiveVariantId("main", NOW);
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion target = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        ProjectVariant activeVariant = new ProjectVariant("main", "main", "v0001", "v0001", true, NOW);

        Optional<List<PreparedChunkBatch>> decoded = invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                List.of(root, target),
                List.of(activeVariant),
                target
        );

        assertTrue(decoded.isPresent());
    }

    @Test
    void directWorldRootRestoreSurvivesLegacyMissingBaselineChunk(@TempDir Path tempDir)
            throws Throwable {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(new StoredBlockChange(
                        new BlockPoint(-145, 64, -200),
                        new StatePayload(state("minecraft:stone"), null),
                        new StatePayload(state("minecraft:air"), null),
                        true
                )),
                List.of()
        ));
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW)
                .withActiveVariantId("main", NOW);
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion head = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        ProjectVariant activeVariant = new ProjectVariant("main", "main", "v0001", "v0002", true, NOW);

        Optional<List<PreparedChunkBatch>> decoded = invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                List.of(root, head),
                List.of(activeVariant),
                root
        );

        assertTrue(decoded.isPresent());
    }

    @Test
    void directRestoreRollsBackPendingTntToTargetAir(@TempDir Path tempDir) throws Throwable {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        BlockPoint pos = new BlockPoint(1, 64, 1);
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), snapshotWithState("minecraft:air"));
        BuildProject project = BuildProject.create(
                        "project",
                        "minecraft:overworld",
                        new Bounds3i(new BlockPoint(0, 0, 0), new BlockPoint(16, 128, 16)),
                        new BlockPoint(0, 64, 0),
                        NOW
                )
                .withActiveVariantId("main", NOW);
        ProjectVersion target = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        ProjectVariant activeVariant = new ProjectVariant("main", "main", "v0001", "v0001", true, NOW);
        RecoveryDraft pendingDraft = new RecoveryDraft(
                "project",
                "main",
                "v0001",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(new StoredBlockChange(
                        pos,
                        StatePayload.air(),
                        new StatePayload(state("minecraft:tnt"), null)
                ))
        );

        Optional<List<PreparedChunkBatch>> decoded = invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                List.of(target),
                List.of(activeVariant),
                target,
                pendingDraft
        );

        assertTrue(decoded.isPresent());
        List<PreparedBlockPlacement> placements = decoded.orElseThrow().stream()
                .flatMap(batch -> batch.placements().stream())
                .filter(placement -> placement.pos().equals(pos.toBlockPos()))
                .toList();
        assertTrue(placements.stream().anyMatch(placement -> placement.state().isAir()));
        assertFalse(placements.stream().anyMatch(placement -> placement.state().is(Blocks.TNT)));
    }

    @Test
    void directRestoreFiltersExcludedEntityPatchDeltas(@TempDir Path tempDir) throws Throwable {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String tntId = "00000000-0000-0000-0000-000000000090";
        String displayId = "00000000-0000-0000-0000-000000000091";
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(),
                List.of(
                        new StoredEntityChange(tntId, "minecraft:tnt", null, entity("minecraft:tnt", tntId, 1.0D)),
                        new StoredEntityChange(displayId, "minecraft:block_display", null, entity("minecraft:block_display", displayId, 2.0D))
                )
        ));
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW)
                .withActiveVariantId("main", NOW);
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion target = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        ProjectVariant activeVariant = new ProjectVariant("main", "main", "v0001", "v0001", true, NOW);

        Optional<List<PreparedChunkBatch>> decoded = invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                List.of(root, target),
                List.of(activeVariant),
                target,
                RestoreEntityTypeSelection.excludeTypes(List.of("minecraft:tnt"))
        );

        assertTrue(decoded.isPresent());
        List<String> spawnedTypes = decoded.orElseThrow().stream()
                .flatMap(batch -> batch.entityBatch().entitiesToSpawn().stream())
                .map(tag -> tag.getString("id").orElse(""))
                .toList();
        assertEquals(List.of("minecraft:block_display"), spawnedTypes);
    }

    @Test
    void targetBlockStatesResolveSnapshotAndPatchChain(@TempDir Path tempDir) throws Exception {
        BlockTargetStateResolver resolver = new BlockTargetStateResolver();
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

        var states = resolver.resolve(
                layout,
                project("main"),
                List.of(initial, target),
                target,
                List.of(pos)
        );

        assertEquals("minecraft:air", states.get(pos).blockId());
    }

    @Test
    void targetBlockStatesSkipMissingBaselineChunksAndKeepAvailableStates(@TempDir Path tempDir) throws Exception {
        BlockTargetStateResolver resolver = new BlockTargetStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        BlockPoint available = new BlockPoint(1, 64, 1);
        BlockPoint missing = new BlockPoint(16, 64, 1);
        this.snapshotWriter.writeFile(
                new BaselineChunkRepository().filePath(layout, new ChunkPoint(0, 0)),
                snapshotWithState("minecraft:stone")
        );
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);

        var states = resolver.resolveOrEmptyWhenBaselineMissing(
                layout,
                project("main"),
                List.of(root),
                root,
                List.of(available, missing)
        );

        assertEquals("minecraft:stone", states.get(available).blockId());
        assertFalse(states.containsKey(missing));
    }

    @Test
    void pendingBlockRollbackUsesTargetStateInsteadOfDraftOldValue(@TempDir Path tempDir) throws Exception {
        BlockTargetStateResolver resolver = new BlockTargetStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        BlockPoint pos = new BlockPoint(1, 64, 1);
        this.snapshotWriter.writeFile(
                layout.snapshotFile("snapshot-0001"),
                snapshotWithState("minecraft:stone")
        );
        ProjectVersion target = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0001",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(new StoredBlockChange(
                        pos,
                        StatePayload.air(),
                        new StatePayload(state("minecraft:stone"), null)
                ))
        );

        RecoveryDraft aligned = resolver.alignPendingRollbackWithTarget(
                layout,
                project("main"),
                List.of(target),
                target,
                draft
        );

        assertTrue(aligned.changes().isEmpty());
    }

    @Test
    void pendingBlockRollbackToDraftBaseSkipsBaselineLookupForWorldRootLineage(@TempDir Path tempDir) throws Exception {
        BlockTargetStateResolver resolver = new BlockTargetStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        BlockPoint pos = new BlockPoint(32, 64, 0);
        ProjectVersion root = version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT);
        RecoveryDraft draft = new RecoveryDraft(
                "project",
                "main",
                "v0001",
                "Alex",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(new StoredBlockChange(
                        pos,
                        new StatePayload(state("minecraft:stone"), null),
                        new StatePayload(state("minecraft:dirt"), null)
                ))
        );

        RecoveryDraft aligned = resolver.alignPendingRollbackWithTarget(
                layout,
                BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW),
                List.of(root),
                root,
                draft
        );

        assertEquals("minecraft:stone", aligned.changes().getFirst().oldValue().blockId());
    }

    @Test
    void detectsMechanismPayloadsForTargetStatePartialRestoreFallback() {
        RestoreMechanismReconciliationPlanner planner = new RestoreMechanismReconciliationPlanner();

        assertTrue(planner.containsMechanismState(List.of(change(1, "minecraft:redstone_wire", "minecraft:air"))));
        assertTrue(planner.containsMechanismState(List.of(change(1, "minecraft:redstone_block", "minecraft:air"))));
        assertTrue(planner.containsMechanismState(List.of(change(1, "minecraft:stone", "minecraft:comparator"))));
        assertTrue(planner.containsMechanismState(List.of(change(
                1,
                state("minecraft:powered_rail", "powered", "false"),
                state("minecraft:powered_rail", "powered", "true")
        ))));
        assertFalse(planner.containsMechanismState(List.of(change(1, "minecraft:dirt", "minecraft:stone"))));
    }

    @Test
    void partialRestoreRejectsMechanismPatchReplayWhenTargetStateLacksSelectedChunk(@TempDir Path tempDir)
            throws Throwable {
        RestoreService service = new RestoreService();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        BlockPoint mechanism = new BlockPoint(1, 64, 1);
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), snapshotInChunk(new ChunkPoint(1, 0)));
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(new StoredBlockChange(
                        mechanism,
                        StatePayload.air(),
                        new StatePayload(state("minecraft:redstone_wire"), null)
                )),
                List.of()
        ));
        BuildProject project = BuildProject.create(
                "project",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 64, 15)),
                new BlockPoint(0, 64, 0),
                NOW
        ).withActiveVariantId("main", NOW);
        ProjectVersion target = version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL);
        ProjectVersion head = version("v0002", "main", "v0001", "", List.of("patch-0002"));
        ProjectVariant activeVariant = new ProjectVariant("main", "main", "v0001", "v0002", true, NOW);
        PartialRestoreRequest request = new PartialRestoreRequest(
                "project",
                "v0001",
                new Bounds3i(mechanism, mechanism),
                PartialRestoreMode.SELECTED_AREA,
                PartialRestoreRegionSource.MANUAL_BOUNDS,
                "tester",
                java.util.Map.of()
        );

        PartialRestoreTargetStateUnavailableException exception = assertThrows(
                PartialRestoreTargetStateUnavailableException.class,
                () -> invokeBuildPartialRestoreDraft(
                        service,
                        layout,
                        project,
                        List.of(target, head),
                        List.of(activeVariant),
                        activeVariant,
                        target,
                        null,
                        request
                ));

        assertTrue(exception.getMessage().contains("missing snapshot or baseline chunks"));
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
    void pendingEntityRollbackToDraftBaseSkipsSnapshotLookupForWorldRootLineage(@TempDir Path tempDir) throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
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

        RecoveryDraft aligned = resolver.alignPendingEntityRollbackWithTarget(layout, List.of(root, target), target, draft);

        assertEquals(1.0D, x(aligned.entityChanges().getFirst().oldValue()));
        assertEquals(2.0D, x(aligned.entityChanges().getFirst().newValue()));
    }

    @Test
    void pendingEntityRollbackCanResolveTargetStateFromWorldRootPatchChain(@TempDir Path tempDir) throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
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

        RecoveryDraft aligned = resolver.alignPendingEntityRollbackWithTarget(layout, List.of(root, target), target, draft);

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

        DirectRestorePatchPlan plan = new DirectRestorePatchPlanner().plan(project, versions, variants, versions.get(1));
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
    void authoritativeEntityReplacementUsesEntityCheckpointOverPatchChain(@TempDir Path tempDir) throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000080";
        EntityPayload tnt = entity("minecraft:tnt", entityId, 1.0D);
        this.snapshotWriter.writeFile(layout.entityCheckpointFile("entity-checkpoint-0002"), snapshot(List.of()));
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(),
                List.of(new StoredEntityChange(entityId, "minecraft:tnt", null, tnt))
        ));
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT),
                version("v0002", "main", "v0001", "", "entity-checkpoint-0002", List.of("patch-0002"))
        );

        List<PreparedChunkBatch> batches = resolver.authoritativeEntityReplacementBatches(
                layout,
                versions,
                "v0002",
                List.of(new ChunkPoint(0, 0))
        );

        assertEquals(1, batches.size());
        assertEquals(0, batches.getFirst().entityBatch().entitiesToUpdate().size());
    }

    @Test
    void authoritativeEntityReplacementAddsEntityCheckpointChunksWithoutBlockBatches(@TempDir Path tempDir)
            throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String entityId = "00000000-0000-0000-0000-000000000089";
        String crystalId = "00000000-0000-0000-0000-000000000090";
        this.snapshotWriter.writeFile(layout.entityCheckpointFile("entity-checkpoint-0002"), snapshot(List.of(
                entity("minecraft:cow", entityId, 1.0D),
                entity("minecraft:end_crystal", crystalId, 2.0D)
        )));
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT),
                version("v0002", "main", "v0001", "", "entity-checkpoint-0002", List.of())
        );

        List<PreparedChunkBatch> batches = resolver.withAuthoritativeEntityReplacementBatches(
                layout,
                versions,
                "v0002",
                List.of()
        );

        assertEquals(1, batches.size());
        assertEquals(new ChunkPoint(0, 0), batches.getFirst().chunk());
        assertTrue(batches.getFirst().entityBatch().replaceEntities());
        List<String> updatedTypes = batches.getFirst().entityBatch().entitiesToUpdate().stream()
                .map(tag -> tag.getString("id").orElse(""))
                .toList();
        assertEquals(List.of("minecraft:cow", "minecraft:end_crystal"), updatedTypes);
    }

    @Test
    void authoritativeEntityReplacementCanSkipEntityTypesFromCheckpoint(@TempDir Path tempDir) throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String tntId = "00000000-0000-0000-0000-000000000081";
        String displayId = "00000000-0000-0000-0000-000000000082";
        this.snapshotWriter.writeFile(layout.entityCheckpointFile("entity-checkpoint-0002"), snapshot(List.of(
                entity("minecraft:tnt", tntId, 1.0D),
                entity("minecraft:block_display", displayId, 2.0D)
        )));
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", "", List.of(), VersionKind.WORLD_ROOT),
                version("v0002", "main", "v0001", "", "entity-checkpoint-0002", List.of())
        );

        List<PreparedChunkBatch> batches = resolver.authoritativeEntityReplacementBatches(
                layout,
                versions,
                "v0002",
                List.of(new ChunkPoint(0, 0)),
                RestoreEntityTypeSelection.excludeTypes(List.of("minecraft:tnt"))
        );

        assertEquals(1, batches.size());
        assertEquals(1, batches.getFirst().entityBatch().entitiesToUpdate().size());
        assertEquals("minecraft:block_display", batches.getFirst().entityBatch().entitiesToUpdate().getFirst().getString("id").orElse(""));
        assertTrue(batches.getFirst().entityBatch().excludedEntityTypes().contains("minecraft:tnt"));
    }

    @Test
    void authoritativeEntityReplacementCanSkipEntityTypesFromSnapshotAndPatchChain(@TempDir Path tempDir)
            throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
        ProjectLayout layout = new ProjectLayout(tempDir.resolve("project.mbp"));
        String snapshotTntId = "00000000-0000-0000-0000-000000000083";
        String displayId = "00000000-0000-0000-0000-000000000084";
        String patchTntId = "00000000-0000-0000-0000-000000000085";
        String armorStandId = "00000000-0000-0000-0000-000000000086";
        this.snapshotWriter.writeFile(layout.snapshotFile("snapshot-0001"), snapshot(List.of(
                entity("minecraft:tnt", snapshotTntId, 1.0D),
                entity("minecraft:block_display", displayId, 2.0D)
        )));
        this.patchMetaRepository.save(layout, this.patchDataRepository.writePayload(
                layout,
                "patch-0002",
                "project",
                "v0002",
                List.of(),
                List.of(
                        new StoredEntityChange(patchTntId, "minecraft:tnt", null, entity("minecraft:tnt", patchTntId, 3.0D)),
                        new StoredEntityChange(armorStandId, "minecraft:armor_stand", null, entity("minecraft:armor_stand", armorStandId, 4.0D))
                )
        ));
        List<ProjectVersion> versions = List.of(
                version("v0001", "main", "", "snapshot-0001", List.of(), VersionKind.INITIAL),
                version("v0002", "main", "v0001", "", List.of("patch-0002"))
        );

        List<PreparedChunkBatch> batches = resolver.authoritativeEntityReplacementBatches(
                layout,
                versions,
                "v0002",
                List.of(new ChunkPoint(0, 0)),
                RestoreEntityTypeSelection.excludeTypes(List.of("minecraft:tnt"))
        );

        List<String> updatedTypes = batches.getFirst().entityBatch().entitiesToUpdate().stream()
                .map(tag -> tag.getString("id").orElse(""))
                .toList();
        assertEquals(List.of("minecraft:block_display", "minecraft:armor_stand"), updatedTypes);
        assertTrue(batches.getFirst().entityBatch().excludedEntityTypes().contains("minecraft:tnt"));
    }

    @Test
    void restoreEntitySelectionFiltersStoredEntityDeltasBeforeApply() throws Throwable {
        RestoreService service = new RestoreService();
        String tntId = "00000000-0000-0000-0000-000000000087";
        String displayId = "00000000-0000-0000-0000-000000000088";

        PreparedWorldChangeBatches analyzed = invokeDecodeStoredChangesAnalyzed(
                service,
                List.of(
                        new StoredEntityChange(tntId, "minecraft:tnt", null, entity("minecraft:tnt", tntId, 1.0D)),
                        new StoredEntityChange(displayId, "minecraft:block_display", null, entity("minecraft:block_display", displayId, 2.0D))
                ),
                RestoreEntityTypeSelection.excludeTypes(List.of("minecraft:tnt"))
        );

        assertEquals(1, analyzed.batches().size());
        assertEquals(1, analyzed.batches().getFirst().entityBatch().entitiesToSpawn().size());
        assertEquals("minecraft:block_display", analyzed.batches()
                .getFirst()
                .entityBatch()
                .entitiesToSpawn()
                .getFirst()
                .getString("id")
                .orElse(""));
    }

    @Test
    void authoritativeEntityReplacementKeepsEmptyTargetChunkAuthoritative(@TempDir Path tempDir) throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
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

        List<PreparedChunkBatch> batches = resolver.authoritativeEntityReplacementBatches(
                layout,
                versions,
                "v0002",
                List.of(new ChunkPoint(0, 0))
        );

        assertEquals(1, batches.size());
        assertEquals(new ChunkPoint(0, 0), batches.getFirst().chunk());
        assertEquals(true, batches.getFirst().entityBatch().replaceEntities());
        assertEquals(0, batches.getFirst().entityBatch().entitiesToUpdate().size());
    }

    @Test
    void authoritativeEntityReplacementRemovesEntityMovedOutOfSelectedChunk(@TempDir Path tempDir) throws Exception {
        RestoreEntityStateResolver resolver = this.entityStateResolver();
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

        List<PreparedChunkBatch> batches = resolver.authoritativeEntityReplacementBatches(
                layout,
                versions,
                "v0002",
                List.of(new ChunkPoint(0, 0))
        );

        assertEquals(1, batches.size());
        assertEquals(new ChunkPoint(0, 0), batches.getFirst().chunk());
        assertEquals(true, batches.getFirst().entityBatch().replaceEntities());
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

    private RestoreEntityStateResolver entityStateResolver() {
        return new RestoreEntityStateResolver(
                new RestoreChunkCollector(this.patchMetaRepository),
                new BaselineChunkRepository(),
                new SnapshotReader(),
                new RestorePayloadLoader(),
                new RestorePlanBuilder(),
                new PreparedChunkBatchCollapser()
        );
    }

    private static Object invokeBuildPartialRestoreDraft(
            RestoreService service,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVariant activeVariant,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            PartialRestoreRequest request
    ) throws Throwable {
        return new PartialRestoreOperationPreparer(new PartialRestoreDiagnosticsLog()).buildDraft(
                layout,
                project,
                versions,
                variants,
                activeVariant,
                targetVersion,
                pendingDraft,
                request,
                (Predicate<BlockPoint>) point -> request.bounds() == null || request.bounds().contains(point),
                64,
                64,
                (io.github.luma.minecraft.world.WorldOperationManager.ProgressSink) (stage, completed, total, detail) -> {
                }
        );
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<PreparedChunkBatch>> invokeTryDecodeDirectRestore(
            RestoreService service,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion
    ) throws Throwable {
        return invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                versions,
                variants,
                targetVersion,
                null,
                RestoreEntityTypeSelection.includeAll()
        );
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<PreparedChunkBatch>> invokeTryDecodeDirectRestore(
            RestoreService service,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft
    ) throws Throwable {
        return invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                versions,
                variants,
                targetVersion,
                pendingDraft,
                RestoreEntityTypeSelection.includeAll()
        );
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<PreparedChunkBatch>> invokeTryDecodeDirectRestore(
            RestoreService service,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion,
            RestoreEntityTypeSelection entityTypeSelection
    ) throws Throwable {
        return invokeTryDecodeDirectRestore(
                service,
                layout,
                project,
                versions,
                variants,
                targetVersion,
                null,
                entityTypeSelection
        );
    }

    @SuppressWarnings("unchecked")
    private static Optional<List<PreparedChunkBatch>> invokeTryDecodeDirectRestore(
            RestoreService service,
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            List<ProjectVariant> variants,
            ProjectVersion targetVersion,
            RecoveryDraft pendingDraft,
            RestoreEntityTypeSelection entityTypeSelection
    ) throws Throwable {
        Method method = RestoreService.class.getDeclaredMethod(
                "tryDecodeDirectRestore",
                ProjectLayout.class,
                io.github.luma.domain.model.BuildProject.class,
                List.class,
                List.class,
                ProjectVersion.class,
                RecoveryDraft.class,
                net.minecraft.server.level.ServerLevel.class,
                RestoreEntityTypeSelection.class,
                WorldOperationManager.ProgressSink.class
        );
        method.setAccessible(true);
        try {
            return (Optional<List<PreparedChunkBatch>>) method.invoke(
                    service,
                    layout,
                    project,
                    versions,
                    variants,
                    targetVersion,
                    pendingDraft,
                    null,
                    entityTypeSelection,
                    (WorldOperationManager.ProgressSink) (stage, completed, total, detail) -> {
                    }
            );
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static PreparedWorldChangeBatches invokeDecodeStoredChangesAnalyzed(
            RestoreService service,
            List<StoredEntityChange> entityChanges,
            RestoreEntityTypeSelection entityTypeSelection
    ) throws Throwable {
        Method method = RestoreService.class.getDeclaredMethod(
                "decodeStoredChangesAnalyzed",
                net.minecraft.server.level.ServerLevel.class,
                List.class,
                List.class,
                boolean.class,
                RestoreEntityTypeSelection.class
        );
        method.setAccessible(true);
        try {
            return (PreparedWorldChangeBatches) method.invoke(
                    service,
                    null,
                    List.of(),
                    entityChanges,
                    true,
                    entityTypeSelection
            );
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }

    private static Object privateRecordAccessor(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
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
        return version(id, variantId, parentVersionId, snapshotId, "", patchIds, versionKind);
    }

    private static ProjectVersion version(
            String id,
            String variantId,
            String parentVersionId,
            String snapshotId,
            String entityCheckpointId,
            List<String> patchIds
    ) {
        return version(id, variantId, parentVersionId, snapshotId, entityCheckpointId, patchIds, VersionKind.MANUAL);
    }

    private static ProjectVersion version(
            String id,
            String variantId,
            String parentVersionId,
            String snapshotId,
            String entityCheckpointId,
            List<String> patchIds,
            VersionKind versionKind
    ) {
        return new ProjectVersion(
                id,
                "project",
                variantId,
                parentVersionId,
                snapshotId,
                entityCheckpointId,
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

    private static StoredBlockChange change(int x, CompoundTag oldState, CompoundTag newState) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 1),
                new StatePayload(oldState, null),
                new StatePayload(newState, null)
        );
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }

    private static CompoundTag state(String blockId, String propertyName, String propertyValue) {
        CompoundTag tag = state(blockId);
        CompoundTag properties = new CompoundTag();
        properties.putString(propertyName, propertyValue);
        tag.put("Properties", properties);
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

    private static SnapshotData snapshotInChunk(ChunkPoint chunk) {
        short[] indexes = new short[4096];
        return new SnapshotData(
                "project",
                NOW,
                64,
                64,
                List.of(new SnapshotChunkData(
                        chunk.x(),
                        chunk.z(),
                        List.of(new SnapshotSectionData(4, List.of(state("minecraft:air")), indexes)),
                        java.util.Map.of(),
                        List.of()
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
        return entity("minecraft:block_display", entityId, x);
    }

    private static EntityPayload entity(String entityType, String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", entityType);
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
