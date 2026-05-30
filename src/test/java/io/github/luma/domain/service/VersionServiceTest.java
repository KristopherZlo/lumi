package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.PatchDataRepository;
import io.github.luma.storage.repository.PatchMetaRepository;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionServiceTest {

    @TempDir
    Path tempDir;

    private final PatchDataRepository patchDataRepository = new PatchDataRepository();
    private final PatchMetaRepository patchMetaRepository = new PatchMetaRepository();

    @Test
    void mergeChangesBuildsParentToCurrentDiffForAmend() {
        List<StoredBlockChange> merged = VersionService.mergeChanges(
                List.of(
                        change(1, "minecraft:stone", "minecraft:dirt"),
                        change(2, "minecraft:oak_planks", "minecraft:glass")
                ),
                List.of(
                        change(1, "minecraft:dirt", "minecraft:gold_block"),
                        change(2, "minecraft:glass", "minecraft:oak_planks")
                )
        );

        assertEquals(1, merged.size());
        assertEquals(new BlockPoint(1, 64, 1), merged.getFirst().pos());
        assertEquals("minecraft:stone", merged.getFirst().oldValue().blockId());
        assertEquals("minecraft:gold_block", merged.getFirst().newValue().blockId());
    }

    @Test
    void mergeChangesDropsFullReverts() {
        List<StoredBlockChange> merged = VersionService.mergeChanges(
                List.of(change(1, "minecraft:stone", "minecraft:dirt")),
                List.of(change(1, "minecraft:dirt", "minecraft:stone"))
        );

        assertTrue(merged.isEmpty());
    }

    @Test
    void mergeEntityChangesBuildsParentToCurrentDiffForAmend() {
        String entityId = "00000000-0000-0000-0000-000000000001";
        List<StoredEntityChange> merged = VersionService.mergeEntityChanges(
                List.of(entityChange(entityId, 1.0D, 2.0D)),
                List.of(entityChange(entityId, 2.0D, 3.0D))
        );

        assertEquals(1, merged.size());
        assertEquals(1.0D, x(merged.getFirst().oldValue()));
        assertEquals(3.0D, x(merged.getFirst().newValue()));
    }

    @Test
    void mergeEntityChangesDropsFullReverts() {
        String entityId = "00000000-0000-0000-0000-000000000002";
        List<StoredEntityChange> merged = VersionService.mergeEntityChanges(
                List.of(entityChange(entityId, 1.0D, 2.0D)),
                List.of(entityChange(entityId, 2.0D, 1.0D))
        );

        assertTrue(merged.isEmpty());
    }

    @Test
    void buildAmendedDraftPreservesEntityChangesFromHeadPatch() throws Exception {
        VersionService service = new VersionService();
        UUID projectId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("entity-amend.mbp"));
        BuildProject project = project(projectId);
        ProjectVariant activeVariant = new ProjectVariant("main", "main", "v0000", "v0001", true, instant(0));
        String entityId = "00000000-0000-0000-0000-000000000003";

        var metadata = this.patchDataRepository.writePayload(
                layout,
                "patch-1",
                projectId.toString(),
                "v0001",
                List.of(),
                List.of(entityChange(entityId, 1.0D, 2.0D))
        );
        this.patchMetaRepository.save(layout, metadata);

        ProjectVersion headVersion = version(
                "v0001",
                "v0000",
                "",
                VersionKind.MANUAL,
                List.of("patch-1")
        );
        RecoveryDraft draft = new RecoveryDraft(
                projectId.toString(),
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                instant(10),
                instant(20),
                List.of(),
                List.of(entityChange(entityId, 2.0D, 3.0D))
        );

        RecoveryDraft amended = service.buildAmendedDraft(layout, project, activeVariant, headVersion, draft);

        assertTrue(amended.changes().isEmpty());
        assertEquals(1, amended.entityChanges().size());
        assertEquals("v0000", amended.baseVersionId());
        assertEquals(1.0D, x(amended.entityChanges().getFirst().oldValue()));
        assertEquals(3.0D, x(amended.entityChanges().getFirst().newValue()));
    }

    @Test
    void failedOperationDraftWriteKeepsOperationDraftHidden() throws Exception {
        VersionService service = new VersionService();
        RecoveryRepository recoveryRepository = new RecoveryRepository();
        UUID projectId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("failed-operation.mbp"));
        BuildProject project = project(projectId);
        RecoveryDraft draft = new RecoveryDraft(
                projectId.toString(),
                "missing-variant",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                instant(10),
                instant(20),
                List.of(change(1, "minecraft:stone", "minecraft:gold_block"))
        );
        recoveryRepository.saveOperationDraft(layout, draft);

        InvocationTargetException exception = assertThrows(
                InvocationTargetException.class,
                () -> writeVersionFromOperationDraft(service).invoke(
                        service,
                        null,
                        layout,
                        project,
                        draft,
                        "save",
                        "tester",
                        VersionKind.MANUAL,
                        false,
                        "",
                        draft.baseVersionId(),
                        (WorldOperationManager.ProgressSink) (stage, completed, total, detail) -> {
                        }
                )
        );

        assertTrue(exception.getCause() instanceof IllegalArgumentException);
        assertTrue(recoveryRepository.loadDraft(layout).isEmpty());
        assertTrue(recoveryRepository.loadOperationDraft(layout).isPresent());
    }

    @Test
    void stagePartialRestoreVersionWritesVersionWithoutMovingHead() throws Exception {
        VersionService service = new VersionService();
        UUID projectId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("partial-stage.mbp"));
        BuildProject project = project(projectId);
        new ProjectRepository().save(layout, project);
        new VariantRepository().save(layout, List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, instant(0))));
        new VersionRepository().save(layout, version("v0001", "", "", VersionKind.INITIAL));
        RecoveryDraft draft = new RecoveryDraft(
                projectId.toString(),
                "main",
                "v0001",
                "tester",
                WorldMutationSource.RESTORE,
                instant(10),
                instant(20),
                List.of(change(1, "minecraft:stone", "minecraft:gold_block"))
        );

        ProjectVersion staged = service.stagePartialRestoreVersion(
                layout,
                project,
                draft,
                "Partial restore",
                "tester",
                (stage, completed, total, detail) -> {
                }
        );

        assertEquals(VersionKind.PARTIAL_RESTORE, staged.versionKind());
        assertEquals(staged, new VersionRepository().load(layout, staged.id()).orElseThrow());
        assertEquals("v0001", new VariantRepository().loadAll(layout).getFirst().headVersionId());
        assertEquals("main", new ProjectRepository().load(layout).orElseThrow().activeVariantId());
    }

    @Test
    void versionsSinceSnapshotTreatsWorldRootAsAnchor() {
        VersionService service = new VersionService();
        List<ProjectVersion> versions = List.of(
                version("v0001", "", "", VersionKind.WORLD_ROOT),
                version("v0002", "v0001", "", VersionKind.MANUAL),
                version("v0003", "v0002", "", VersionKind.MANUAL)
        );

        assertEquals(2, service.versionsSinceSnapshot(versions, "v0003"));
    }

    @Test
    void chunkBoundsWrapExactTouchedChunkSpan() {
        Bounds3i bounds = PreviewBoundsResolver.chunkBounds(
                List.of(new ChunkPoint(10, 20), new ChunkPoint(11, 22)),
                -64,
                319
        );

        assertEquals(new BlockPoint(160, -64, 320), bounds.min());
        assertEquals(new BlockPoint(191, 319, 367), bounds.max());
    }

    private static StoredBlockChange change(int x, String leftBlockId, String rightBlockId) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, x),
                payload(leftBlockId),
                payload(rightBlockId)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }

    private static ProjectVersion version(String id, String parentId, String snapshotId, VersionKind versionKind) {
        return version(id, parentId, snapshotId, versionKind, List.of());
    }

    private static ProjectVersion version(
            String id,
            String parentId,
            String snapshotId,
            VersionKind versionKind,
            List<String> patchIds
    ) {
        return new ProjectVersion(
                id,
                "project",
                "main",
                parentId,
                snapshotId,
                patchIds,
                versionKind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                Instant.parse("2026-04-21T00:00:00Z")
        );
    }

    private static BuildProject project(UUID projectId) {
        return new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                projectId,
                "Tower",
                "",
                "1.21.11",
                "fabric",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                new BlockPoint(0, 64, 0),
                "main",
                "main",
                instant(0),
                instant(0),
                ProjectSettings.defaults(),
                false,
                false
        );
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-21T00:00:00Z").plusSeconds(seconds);
    }

    private static Method writeVersionFromOperationDraft(VersionService service) throws NoSuchMethodException {
        Method method = service.getClass().getDeclaredMethod(
                "writeVersionFromOperationDraft",
                net.minecraft.server.level.ServerLevel.class,
                ProjectLayout.class,
                BuildProject.class,
                RecoveryDraft.class,
                String.class,
                String.class,
                VersionKind.class,
                boolean.class,
                String.class,
                String.class,
                WorldOperationManager.ProgressSink.class
        );
        method.setAccessible(true);
        return method;
    }

    private static StoredEntityChange entityChange(String entityId, double oldX, double newX) {
        return new StoredEntityChange(
                entityId,
                "minecraft:block_display",
                entity(entityId, oldX),
                entity(entityId, newX)
        );
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

    private static double x(EntityPayload payload) {
        return payload.entityTag().getListOrEmpty("Pos").getDoubleOr(0, 0.0D);
    }
}
