package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.MergeConflictResolution;
import io.github.luma.domain.model.MergeConflictZoneResolution;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VariantMergePlan;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantMergeServiceTest {

    @TempDir
    Path tempDir;

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();
    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final VariantMergeService variantMergeService = new VariantMergeService();

    @Test
    void planMergeBuildsOverlayChangesWhenVariantsTouchDifferentBlocks() throws Exception {
        UUID projectId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        ProjectLayout targetLayout = this.seedTargetProject(this.tempDir.resolve("tower.mbp"), projectId, false);
        ProjectLayout sourceLayout = this.seedSourceProject(this.tempDir.resolve("tower-shared.mbp"), projectId, false);
        BuildProject targetProject = this.projectRepository.load(targetLayout).orElseThrow();
        BuildProject sourceProject = this.projectRepository.load(sourceLayout).orElseThrow();

        VariantMergePlan plan = this.variantMergeService.planMerge(
                targetLayout,
                targetProject,
                "main",
                sourceLayout,
                sourceProject,
                "roof-pass"
        );

        assertEquals("v0001", plan.commonAncestorVersionId());
        assertFalse(plan.hasConflicts());
        assertEquals(1, plan.mergeBlockCount());
        assertEquals(new BlockPoint(8, 65, 8), plan.mergeChanges().getFirst().pos());
    }

    @Test
    void planMergeReportsBlockConflictsWhenBothVariantsChangeTheSamePosition() throws Exception {
        UUID projectId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ProjectLayout targetLayout = this.seedTargetProject(this.tempDir.resolve("tower-conflict.mbp"), projectId, true);
        ProjectLayout sourceLayout = this.seedSourceProject(this.tempDir.resolve("tower-conflict-shared.mbp"), projectId, true);
        BuildProject targetProject = this.projectRepository.load(targetLayout).orElseThrow();
        BuildProject sourceProject = this.projectRepository.load(sourceLayout).orElseThrow();

        VariantMergePlan plan = this.variantMergeService.planMerge(
                targetLayout,
                targetProject,
                "main",
                sourceLayout,
                sourceProject,
                "roof-pass"
        );

        assertTrue(plan.hasConflicts());
        assertEquals(1, plan.conflictZones().size());
        assertEquals(1, plan.conflictPositions().size());
        assertEquals(new BlockPoint(4, 65, 4), plan.conflictZones().getFirst().positions().getFirst());
        assertEquals(1, plan.conflictChunkCount());
        assertTrue(plan.mergeChanges().isEmpty());
    }

    @Test
    void planMergeGroupsNeighboringConflictChunksIntoStableZones() throws Exception {
        UUID projectId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        ProjectLayout targetLayout = this.seedProject(layout(this.tempDir.resolve("tower-zones.mbp")), projectId, "Tower", List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, instant(0))
        ));
        ProjectLayout sourceLayout = this.seedProject(layout(this.tempDir.resolve("tower-zones-shared.mbp")), projectId, "Tower Shared", List.of(
                new ProjectVariant("roof-pass", "Roof pass", "v0001", "v0003", true, instant(60))
        ));
        this.writeVersion(targetLayout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(targetLayout, projectId, "v0002", "main", "v0001", List.of(
                new StoredBlockChange(new BlockPoint(4, 65, 4), state("minecraft:air"), state("minecraft:stone")),
                new StoredBlockChange(new BlockPoint(20, 65, 4), state("minecraft:air"), state("minecraft:stone")),
                new StoredBlockChange(new BlockPoint(80, 65, 80), state("minecraft:air"), state("minecraft:stone"))
        ));
        this.writeVersion(sourceLayout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(sourceLayout, projectId, "v0003", "roof-pass", "v0001", List.of(
                new StoredBlockChange(new BlockPoint(4, 65, 4), state("minecraft:air"), state("minecraft:glass")),
                new StoredBlockChange(new BlockPoint(20, 65, 4), state("minecraft:air"), state("minecraft:glass")),
                new StoredBlockChange(new BlockPoint(80, 65, 80), state("minecraft:air"), state("minecraft:glass"))
        ));

        BuildProject targetProject = this.projectRepository.load(targetLayout).orElseThrow();
        BuildProject sourceProject = this.projectRepository.load(sourceLayout).orElseThrow();
        VariantMergePlan plan = this.variantMergeService.planMerge(
                targetLayout,
                targetProject,
                "main",
                sourceLayout,
                sourceProject,
                "roof-pass"
        );

        assertEquals(2, plan.conflictZones().size());
        assertEquals(2, plan.conflictZones().getFirst().chunkCount());
        assertEquals(2, plan.conflictZones().getFirst().blockCount());
        assertEquals(new BlockPoint(4, 65, 4), plan.conflictZones().getFirst().bounds().min());
        assertEquals(new BlockPoint(20, 65, 4), plan.conflictZones().getFirst().bounds().max());
        assertEquals(1, plan.conflictZones().get(1).chunkCount());
        assertEquals(new BlockPoint(80, 65, 80), plan.conflictZones().get(1).positions().getFirst());
    }

    @Test
    void resolveMergeChangesUsesConflictResolutionsPerZone() throws Exception {
        UUID projectId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        ProjectLayout targetLayout = this.seedTargetProject(this.tempDir.resolve("tower-resolve.mbp"), projectId, false);
        ProjectLayout sourceLayout = this.seedProject(layout(this.tempDir.resolve("tower-resolve-shared.mbp")), projectId, "Tower Shared", List.of(
                new ProjectVariant("roof-pass", "Roof pass", "v0001", "v0003", true, instant(60))
        ));
        this.writeVersion(sourceLayout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(sourceLayout, projectId, "v0003", "roof-pass", "v0001", List.of(
                new StoredBlockChange(new BlockPoint(8, 65, 8), state("minecraft:air"), state("minecraft:oak_planks")),
                new StoredBlockChange(new BlockPoint(4, 65, 4), state("minecraft:air"), state("minecraft:glass"))
        ));
        this.writeVersion(targetLayout, projectId, "v0002", "main", "v0001", List.of(
                new StoredBlockChange(new BlockPoint(4, 65, 4), state("minecraft:air"), state("minecraft:stone"))
        ));

        BuildProject targetProject = this.projectRepository.load(targetLayout).orElseThrow();
        BuildProject sourceProject = this.projectRepository.load(sourceLayout).orElseThrow();
        VariantMergePlan plan = this.variantMergeService.planMerge(
                targetLayout,
                targetProject,
                "main",
                sourceLayout,
                sourceProject,
                "roof-pass"
        );

        assertEquals(1, plan.mergeChanges().size());
        assertEquals(1, plan.conflictZones().size());
        assertEquals(1, this.variantMergeService.resolveMergeChanges(plan, List.of(
                new MergeConflictZoneResolution(plan.conflictZones().getFirst().id(), MergeConflictResolution.KEEP_LOCAL)
        )).size());
        assertEquals(2, this.variantMergeService.resolveMergeChanges(plan, List.of(
                new MergeConflictZoneResolution(plan.conflictZones().getFirst().id(), MergeConflictResolution.USE_IMPORTED)
        )).size());
        Assertions.assertThrows(IllegalArgumentException.class, () -> this.variantMergeService.resolveMergeChanges(plan, List.of()));
    }

    @Test
    void planMergeCarriesSourceOnlyEntityChanges() throws Exception {
        UUID projectId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        ProjectLayout targetLayout = this.seedProject(layout(this.tempDir.resolve("tower-entity.mbp")), projectId, "Tower", List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, instant(0))
        ));
        ProjectLayout sourceLayout = this.seedProject(layout(this.tempDir.resolve("tower-entity-shared.mbp")), projectId, "Tower Shared", List.of(
                new ProjectVariant("roof-pass", "Roof pass", "v0001", "v0003", true, instant(60))
        ));
        String entityId = "00000000-0000-0000-0000-000000000101";
        this.writeVersion(targetLayout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(targetLayout, projectId, "v0002", "main", "v0001", List.of());
        this.writeVersion(sourceLayout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(sourceLayout, projectId, "v0003", "roof-pass", "v0001", List.of(), List.of(
                new StoredEntityChange(entityId, "minecraft:block_display", null, entity(entityId, 8.0D))
        ));

        VariantMergePlan plan = this.variantMergeService.planMerge(
                targetLayout,
                this.projectRepository.load(targetLayout).orElseThrow(),
                "main",
                sourceLayout,
                this.projectRepository.load(sourceLayout).orElseThrow(),
                "roof-pass"
        );

        assertEquals(0, plan.mergeBlockCount());
        assertEquals(1, plan.mergeEntityCount());
        assertTrue(plan.canApply(List.of()));
        assertEquals(entityId, plan.mergeEntityChanges().getFirst().entityId());
    }

    @Test
    void planMergeRejectsUnresolvableEntityConflicts() throws Exception {
        UUID projectId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        ProjectLayout targetLayout = this.seedProject(layout(this.tempDir.resolve("tower-entity-conflict.mbp")), projectId, "Tower", List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, instant(0))
        ));
        ProjectLayout sourceLayout = this.seedProject(layout(this.tempDir.resolve("tower-entity-conflict-shared.mbp")), projectId, "Tower Shared", List.of(
                new ProjectVariant("roof-pass", "Roof pass", "v0001", "v0003", true, instant(60))
        ));
        String entityId = "00000000-0000-0000-0000-000000000102";
        this.writeVersion(targetLayout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(targetLayout, projectId, "v0002", "main", "v0001", List.of(), List.of(
                new StoredEntityChange(entityId, "minecraft:block_display", entity(entityId, 1.0D), entity(entityId, 2.0D))
        ));
        this.writeVersion(sourceLayout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(sourceLayout, projectId, "v0003", "roof-pass", "v0001", List.of(), List.of(
                new StoredEntityChange(entityId, "minecraft:block_display", entity(entityId, 1.0D), entity(entityId, 3.0D))
        ));

        Assertions.assertThrows(IllegalArgumentException.class, () -> this.variantMergeService.planMerge(
                targetLayout,
                this.projectRepository.load(targetLayout).orElseThrow(),
                "main",
                sourceLayout,
                this.projectRepository.load(sourceLayout).orElseThrow(),
                "roof-pass"
        ));
    }

    private ProjectLayout seedTargetProject(Path root, UUID projectId, boolean conflict) throws Exception {
        ProjectLayout layout = this.seedProject(layout(root), projectId, "Tower", List.of(
                new ProjectVariant("main", "main", "v0001", "v0002", true, instant(0))
        ));
        this.writeVersion(layout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(layout, projectId, "v0002", "main", "v0001", List.of(new StoredBlockChange(
                conflict ? new BlockPoint(4, 65, 4) : new BlockPoint(2, 65, 2),
                state("minecraft:air"),
                state("minecraft:stone")
        )));
        return layout;
    }

    private ProjectLayout seedSourceProject(Path root, UUID projectId, boolean conflict) throws Exception {
        ProjectLayout layout = this.seedProject(layout(root), projectId, "Tower - Shared", List.of(
                new ProjectVariant("roof-pass", "Roof pass", "v0001", "v0003", true, instant(60))
        ));
        this.writeVersion(layout, projectId, "v0001", "main", "", List.of());
        this.writeVersion(layout, projectId, "v0003", "roof-pass", "v0001", List.of(new StoredBlockChange(
                conflict ? new BlockPoint(4, 65, 4) : new BlockPoint(8, 65, 8),
                state("minecraft:air"),
                state(conflict ? "minecraft:glass" : "minecraft:oak_planks")
        )));
        return layout;
    }

    private ProjectLayout seedProject(ProjectLayout layout, UUID projectId, String projectName, List<ProjectVariant> variants) throws Exception {
        this.projectRepository.initializeLayout(layout);
        BuildProject project = new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                projectId,
                projectName,
                "",
                "1.21.11",
                "fabric",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                variants.getFirst().id(),
                variants.getFirst().id(),
                instant(0),
                instant(0),
                io.github.luma.domain.model.ProjectSettings.defaults(),
                false,
                false
        );
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, variants);
        return layout;
    }

    private void writeVersion(
            ProjectLayout layout,
            UUID projectId,
            String versionId,
            String variantId,
            String parentVersionId,
            List<StoredBlockChange> changes
    ) throws Exception {
        this.writeVersion(layout, projectId, versionId, variantId, parentVersionId, changes, List.of());
    }

    private void writeVersion(
            ProjectLayout layout,
            UUID projectId,
            String versionId,
            String variantId,
            String parentVersionId,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges
    ) throws Exception {
        String patchId = changes.isEmpty() && entityChanges.isEmpty() ? "" : "patch-" + versionId.substring(1);
        if (!patchId.isBlank()) {
            var metadata = this.patchDataRepository.writePayload(
                    layout,
                    patchId,
                    projectId.toString(),
                    versionId,
                    changes,
                    entityChanges
            );
            this.patchMetaRepository.save(layout, metadata);
        }
        this.versionRepository.save(layout, new ProjectVersion(
                versionId,
                projectId.toString(),
                variantId,
                parentVersionId,
                "",
                patchId.isBlank() ? List.of() : List.of(patchId),
                "v0001".equals(versionId) ? VersionKind.INITIAL : VersionKind.MANUAL,
                "tester",
                versionId,
                ChangeStats.empty(),
                io.github.luma.domain.model.PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                instant("v0001".equals(versionId) ? 0 : 120)
        ));
    }

    private static ProjectLayout layout(Path root) {
        return new ProjectLayout(root);
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }

    private static StatePayload state(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }

    private static EntityPayload entity(String entityId, double x) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", "minecraft:block_display");
        tag.putString("UUID", entityId);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(64.0D));
        pos.add(DoubleTag.valueOf(1.0D));
        tag.put("Pos", pos);
        return new EntityPayload(tag);
    }
}
