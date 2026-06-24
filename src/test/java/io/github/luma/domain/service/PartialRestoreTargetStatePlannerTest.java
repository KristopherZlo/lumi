package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.SnapshotChunkData;
import io.github.luma.domain.model.SnapshotData;
import io.github.luma.domain.model.SnapshotSectionData;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.BaselineChunkRepository;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.SnapshotWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartialRestoreTargetStatePlannerTest {

    private static final Instant NOW = Instant.parse("2026-04-29T00:00:00Z");

    @TempDir
    Path tempDir;

    private final PartialRestoreTargetStatePlanner planner = new PartialRestoreTargetStatePlanner();
    private final SnapshotWriter snapshotWriter = new SnapshotWriter();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final BaselineChunkRepository baselineChunkRepository = new BaselineChunkRepository();

    @Test
    void plansNonDirectSelectedAreaFromCurrentAndTargetStates() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = boundedProject();
        writeSnapshot(layout, "current-root", Map.of(point(1), "minecraft:stone"), List.of());
        writeSnapshot(layout, "target-root", Map.of(point(1), "minecraft:stone"), List.of());
        writePatch(layout, "current-patch", "current-head",
                List.of(change(point(1), "minecraft:stone", "minecraft:gold_block")), List.of());
        writePatch(layout, "target-patch", "target-head",
                List.of(change(point(1), "minecraft:stone", "minecraft:diamond_block")), List.of());
        ProjectVersion currentRoot = version("current-root", "", "current-root", List.of(), VersionKind.INITIAL);
        ProjectVersion currentHead = version("current-head", "current-root", "", List.of("current-patch"),
                VersionKind.MANUAL);
        ProjectVersion targetRoot = version("target-root", "", "target-root", List.of(), VersionKind.INITIAL);
        ProjectVersion targetHead = version("target-head", "target-root", "", List.of("target-patch"),
                VersionKind.MANUAL);

        PartialRestoreTargetStatePlanner.Plan plan = this.plan(layout, project,
                List.of(currentRoot, currentHead, targetRoot, targetHead), currentHead, targetHead);

        assertEquals(1, plan.blockChanges().size());
        assertEquals("minecraft:gold_block", plan.blockChanges().getFirst().oldValue().blockId());
        assertEquals("minecraft:diamond_block", plan.blockChanges().getFirst().newValue().blockId());
    }

    @Test
    void outsideSelectionPreservesSelectedBlocks() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = boundedProject();
        writeSnapshot(layout, "current", Map.of(point(1), "minecraft:gold_block", point(2), "minecraft:gold_block"),
                List.of());
        writeSnapshot(layout, "target",
                Map.of(point(1), "minecraft:diamond_block", point(2), "minecraft:diamond_block"), List.of());
        ProjectVersion current = version("current", "", "current", List.of(), VersionKind.INITIAL);
        ProjectVersion target = version("target", "", "target", List.of(), VersionKind.INITIAL);

        PartialRestoreTargetStatePlanner.Plan plan = this.planner.plan(
                layout,
                project,
                List.of(current, target),
                current,
                target,
                null,
                new Bounds3i(point(1), point(1)),
                PartialRestoreMode.OUTSIDE_SELECTED_AREA,
                64,
                64,
                noop());

        assertEquals(List.of(point(2)), plan.blockChanges().stream().map(StoredBlockChange::pos).toList());
    }

    @Test
    void selectedAreaClipsTargetStateScopeToBoundedProject() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = boundedProject();
        writeSnapshot(layout, "current", Map.of(point(1), "minecraft:gold_block"), List.of());
        writeSnapshot(layout, "target", Map.of(point(1), "minecraft:diamond_block"), List.of());
        ProjectVersion current = version("current", "", "current", List.of(), VersionKind.INITIAL);
        ProjectVersion target = version("target", "", "target", List.of(), VersionKind.INITIAL);

        PartialRestoreTargetStatePlanner.Plan plan = this.planner.plan(
                layout,
                project,
                List.of(current, target),
                current,
                target,
                null,
                new Bounds3i(point(1), new BlockPoint(20, 64, 0)),
                PartialRestoreMode.SELECTED_AREA,
                64,
                64,
                noop());

        assertEquals(List.of(point(1)), plan.blockChanges().stream().map(StoredBlockChange::pos).toList());
    }

    @Test
    void hardScopeKeepsTargetStateRestoreInsideComplexZoneCells() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = BuildProject.create(
                "project",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 95, 0)),
                point(0),
                NOW);
        BlockPoint allowed = new BlockPoint(1, 64, 0);
        BlockPoint sameBoundsDifferentCell = new BlockPoint(1, 80, 0);
        writeSnapshot(layout, "current-root", Map.of(), List.of());
        writeSnapshot(layout, "target-root", Map.of(), List.of());
        writePatch(layout, "current-patch", "current-head",
                List.of(
                        change(allowed, "minecraft:stone", "minecraft:gold_block"),
                        change(sameBoundsDifferentCell, "minecraft:stone", "minecraft:gold_block")),
                List.of());
        writePatch(layout, "target-patch", "target-head",
                List.of(
                        change(allowed, "minecraft:stone", "minecraft:diamond_block"),
                        change(sameBoundsDifferentCell, "minecraft:stone", "minecraft:diamond_block")),
                List.of());
        ProjectVersion currentRoot = version("current-root", "", "current-root", List.of(), VersionKind.INITIAL);
        ProjectVersion currentHead = version("current-head", "current-root", "", List.of("current-patch"),
                VersionKind.MANUAL);
        ProjectVersion targetRoot = version("target-root", "", "target-root", List.of(), VersionKind.INITIAL);
        ProjectVersion targetHead = version("target-head", "target-root", "", List.of("target-patch"),
                VersionKind.MANUAL);

        PartialRestoreTargetStatePlanner.Plan plan = this.planner.plan(
                layout,
                project,
                List.of(currentRoot, currentHead, targetRoot, targetHead),
                currentHead,
                targetHead,
                null,
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 95, 0)),
                PartialRestoreMode.SELECTED_AREA,
                64,
                95,
                noop(),
                point -> point.y() < 80);

        assertEquals(List.of(allowed), plan.blockChanges().stream().map(StoredBlockChange::pos).toList());
    }

    @Test
    void fillsWholeDimensionTargetStateFromBaselineChunks() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW);
        this.snapshotWriter.writeFile(
                this.baselineChunkRepository.filePath(layout, new ChunkPoint(0, 0)),
                snapshot(Map.of(point(1), "minecraft:stone"), List.of()));
        writePatch(layout, "target-patch", "target",
                List.of(change(point(1), "minecraft:stone", "minecraft:diamond_block")), List.of());
        ProjectVersion root = version("root", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion target = version("target", "root", "", List.of("target-patch"), VersionKind.MANUAL);

        PartialRestoreTargetStatePlanner.Plan plan = this.plan(layout, project, List.of(root, target), root, target);

        assertEquals(1, plan.blockChanges().size());
        assertEquals("minecraft:stone", plan.blockChanges().getFirst().oldValue().blockId());
        assertEquals("minecraft:diamond_block", plan.blockChanges().getFirst().newValue().blockId());
    }

    @Test
    void selectedWholeDimensionTargetStateSkipsUntrackedAdjacentChunks() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = BuildProject.createWorldWorkspace("project", "minecraft:overworld", NOW);
        BlockPoint changed = point(1);
        this.snapshotWriter.writeFile(
                this.baselineChunkRepository.filePath(layout, new ChunkPoint(0, 0)),
                snapshot(Map.of(changed, "minecraft:stone"), List.of()));
        writePatch(layout, "target-patch", "target",
                List.of(change(changed, "minecraft:stone", "minecraft:diamond_block")), List.of());
        writePatch(layout, "current-patch", "current",
                List.of(change(changed, "minecraft:diamond_block", "minecraft:redstone_wire")), List.of());
        ProjectVersion root = version("root", "", "", List.of(), VersionKind.WORLD_ROOT);
        ProjectVersion target = version("target", "root", "", List.of("target-patch"), VersionKind.MANUAL);
        ProjectVersion current = version("current", "target", "", List.of("current-patch"), VersionKind.MANUAL);

        PartialRestoreTargetStatePlanner.Plan plan = this.planner.plan(
                layout,
                project,
                List.of(root, target, current),
                current,
                target,
                null,
                new Bounds3i(changed, new BlockPoint(20, 64, 0)),
                PartialRestoreMode.SELECTED_AREA,
                64,
                64,
                noop());

        assertEquals(1, plan.blockChanges().size());
        assertEquals(changed, plan.blockChanges().getFirst().pos());
        assertEquals("minecraft:redstone_wire", plan.blockChanges().getFirst().oldValue().blockId());
        assertEquals("minecraft:diamond_block", plan.blockChanges().getFirst().newValue().blockId());
    }

    @Test
    void pendingDraftBecomesCurrentState() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = boundedProject();
        writeSnapshot(layout, "current", Map.of(point(1), "minecraft:stone"), List.of());
        writeSnapshot(layout, "target", Map.of(point(1), "minecraft:diamond_block"), List.of());
        ProjectVersion current = version("current", "", "current", List.of(), VersionKind.INITIAL);
        ProjectVersion target = version("target", "", "target", List.of(), VersionKind.INITIAL);
        RecoveryDraft pending = new RecoveryDraft(
                project.id().toString(),
                "main",
                current.id(),
                "tester",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                List.of(change(point(1), "minecraft:stone", "minecraft:gold_block")),
                List.of());

        PartialRestoreTargetStatePlanner.Plan plan = this.planner.plan(
                layout,
                project,
                List.of(current, target),
                current,
                target,
                pending,
                new Bounds3i(point(1), point(1)),
                PartialRestoreMode.SELECTED_AREA,
                64,
                64,
                noop());

        assertEquals("minecraft:gold_block", plan.blockChanges().getFirst().oldValue().blockId());
        assertEquals("minecraft:diamond_block", plan.blockChanges().getFirst().newValue().blockId());
    }

    @Test
    void filtersEntitySpawnUpdateAndRemovalToRequestedRegion() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = boundedProject();
        EntityPayload removed = entity("00000000-0000-0000-0000-000000000001", 1.0D);
        EntityPayload updatedCurrent = entity("00000000-0000-0000-0000-000000000003", 2.0D);
        EntityPayload outsideCurrent = entity("00000000-0000-0000-0000-000000000004", 10.0D);
        EntityPayload spawned = entity("00000000-0000-0000-0000-000000000002", 1.0D);
        EntityPayload updatedTarget = entity("00000000-0000-0000-0000-000000000003", 3.0D);
        EntityPayload outsideTarget = entity("00000000-0000-0000-0000-000000000004", 11.0D);
        writeSnapshot(layout, "current", Map.of(), List.of(removed, updatedCurrent, outsideCurrent));
        writeSnapshot(layout, "target", Map.of(), List.of(spawned, updatedTarget, outsideTarget));
        ProjectVersion current = version("current", "", "current", List.of(), VersionKind.INITIAL);
        ProjectVersion target = version("target", "", "target", List.of(), VersionKind.INITIAL);

        PartialRestoreTargetStatePlanner.Plan plan = this.plan(layout, project, List.of(current, target), current,
                target);

        assertEquals(
                List.of(
                        "00000000-0000-0000-0000-000000000001",
                        "00000000-0000-0000-0000-000000000002",
                        "00000000-0000-0000-0000-000000000003"),
                plan.entityChanges().stream().map(StoredEntityChange::entityId).toList());
    }

    @Test
    void rejectsMissingSnapshotOrBaselineTargetStateForBoundedProject() {
        ProjectLayout layout = new ProjectLayout(this.tempDir);
        BuildProject project = boundedProject();
        ProjectVersion root = version("root", "", "", List.of(), VersionKind.WORLD_ROOT);

        assertThrows(IllegalArgumentException.class, () -> this.plan(layout, project, List.of(root), root, root));
    }

    private PartialRestoreTargetStatePlanner.Plan plan(
            ProjectLayout layout,
            BuildProject project,
            List<ProjectVersion> versions,
            ProjectVersion current,
            ProjectVersion target) throws Exception {
        return this.planner.plan(
                layout,
                project,
                versions,
                current,
                target,
                null,
                new Bounds3i(point(0), point(5)),
                PartialRestoreMode.SELECTED_AREA,
                64,
                64,
                noop());
    }

    private void writeSnapshot(
            ProjectLayout layout,
            String snapshotId,
            Map<BlockPoint, String> blocks,
            List<EntityPayload> entities) throws Exception {
        this.snapshotWriter.writeFile(layout.snapshotFile(snapshotId), snapshot(blocks, entities));
    }

    private static SnapshotData snapshot(Map<BlockPoint, String> blocks, List<EntityPayload> entities) {
        List<CompoundTag> palette = List.of(state("minecraft:air"), state("minecraft:stone"),
                state("minecraft:gold_block"), state("minecraft:diamond_block"));
        Map<String, Short> paletteIds = Map.of(
                "minecraft:air", (short) 0,
                "minecraft:stone", (short) 1,
                "minecraft:gold_block", (short) 2,
                "minecraft:diamond_block", (short) 3);
        short[] indexes = new short[4096];
        Map<Integer, CompoundTag> blockEntities = new HashMap<>();
        for (Map.Entry<BlockPoint, String> entry : blocks.entrySet()) {
            BlockPoint pos = entry.getKey();
            indexes[((pos.y() - 64) << 8) | ((pos.z() & 15) << 4) | (pos.x() & 15)] = paletteIds.get(entry.getValue());
        }
        return new SnapshotData(
                "project",
                NOW,
                64,
                64,
                List.of(new SnapshotChunkData(
                        0,
                        0,
                        List.of(new SnapshotSectionData(4, palette, indexes)),
                        blockEntities,
                        entities)));
    }

    private void writePatch(
            ProjectLayout layout,
            String patchId,
            String versionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges) throws Exception {
        this.patchMetaRepository.save(
                layout,
                this.patchDataRepository.writePayload(layout, patchId, "project", versionId, changes, entityChanges));
    }

    private static StoredBlockChange change(BlockPoint pos, String oldBlock, String newBlock) {
        return new StoredBlockChange(pos, new StatePayload(state(oldBlock), null),
                new StatePayload(state(newBlock), null));
    }

    private static BuildProject boundedProject() {
        return BuildProject.create(
                "project",
                "minecraft:overworld",
                new Bounds3i(point(0), point(15)),
                point(0),
                NOW);
    }

    private static ProjectVersion version(
            String id,
            String parentVersionId,
            String snapshotId,
            List<String> patchIds,
            VersionKind kind) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentVersionId,
                snapshotId,
                patchIds,
                kind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW);
    }

    private static BlockPoint point(int x) {
        return new BlockPoint(x, 64, 0);
    }

    private static CompoundTag state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
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

    private static io.github.luma.minecraft.world.WorldOperationManager.ProgressSink noop() {
        return (stage, completed, total, detail) -> {
        };
    }
}
