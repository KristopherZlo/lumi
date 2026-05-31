package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PendingRestoreCompletion;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.ProjectRepository;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import io.github.luma.storage.repository.VersionRepository;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestoreCompletionRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-30T00:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path tempDir;

    private final ProjectRepository projectRepository = new ProjectRepository();
    private final VariantRepository variantRepository = new VariantRepository();
    private final VersionRepository versionRepository = new VersionRepository();
    private final RecoveryRepository recoveryRepository = new RecoveryRepository();
    private final RestoreCompletionRecoveryService service = new RestoreCompletionRecoveryService();

    @Test
    void completesPendingFullRestoreMetadata() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        BuildProject project = project();
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(
                new ProjectVariant("main", "main", "v0001", "v0001", true, NOW),
                new ProjectVariant("feature", "feature", "v0001", "v0002", false, NOW)
        ));
        this.versionRepository.save(layout, version("v0003", "feature", "v0002", VersionKind.MANUAL));
        this.recoveryRepository.saveDraft(layout, draft(List.of(change(1), change(20))));
        this.recoveryRepository.savePendingRestoreCompletion(
                layout,
                PendingRestoreCompletion.full(project.id().toString(), "feature", "v0003", NOW)
        );

        this.service.completePending(layout, project, null);

        assertEquals("feature", this.projectRepository.load(layout).orElseThrow().activeVariantId());
        assertEquals(
                "v0003",
                this.variantRepository.loadAll(layout).stream()
                        .filter(variant -> variant.id().equals("feature"))
                        .findFirst()
                        .orElseThrow()
                        .headVersionId()
        );
        assertTrue(this.recoveryRepository.loadDraft(layout).isEmpty());
        assertTrue(this.recoveryRepository.loadPendingRestoreCompletion(layout).isEmpty());
    }

    @Test
    void completesPendingPartialRestoreWithoutPublishingHead() throws Exception {
        ProjectLayout layout = new ProjectLayout(this.tempDir.resolve("project.mbp"));
        BuildProject project = project();
        this.projectRepository.save(layout, project);
        this.variantRepository.save(layout, List.of(new ProjectVariant("main", "main", "v0001", "v0001", true, NOW)));
        this.versionRepository.save(layout, version("v0002", "main", "v0001", VersionKind.MANUAL));
        this.recoveryRepository.saveDraft(layout, draft(List.of(change(1), change(20))));
        this.recoveryRepository.saveOperationDraft(layout, draft(List.of(change(20), change(30))));
        this.recoveryRepository.savePendingRestoreCompletion(layout, PendingRestoreCompletion.partial(
                project.id().toString(),
                "main",
                "v0002",
                NOW,
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(15, 80, 15)),
                PartialRestoreMode.SELECTED_AREA
        ));

        this.service.completePending(layout, project, null);

        assertEquals("v0001", this.variantRepository.loadAll(layout).getFirst().headVersionId());
        RecoveryDraft remainingDraft = this.recoveryRepository.loadDraft(layout).orElseThrow();
        assertEquals(2, remainingDraft.changes().size());
        assertEquals(new BlockPoint(20, 64, 0), remainingDraft.changes().getFirst().pos());
        assertEquals(new BlockPoint(30, 64, 0), remainingDraft.changes().get(1).pos());
        assertTrue(this.recoveryRepository.loadOperationDraft(layout).isEmpty());
        assertTrue(this.recoveryRepository.loadPendingRestoreCompletion(layout).isEmpty());
    }

    private static BuildProject project() {
        return new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                PROJECT_ID,
                "project",
                "",
                "1.21.11",
                "fabric",
                "minecraft:overworld",
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(31, 80, 31)),
                new BlockPoint(0, 64, 0),
                "main",
                "main",
                NOW,
                NOW,
                io.github.luma.domain.model.ProjectSettings.defaults(),
                false,
                false
        );
    }

    private static ProjectVersion version(String id, String variantId, String parentVersionId, VersionKind kind) {
        return new ProjectVersion(
                id,
                PROJECT_ID.toString(),
                variantId,
                parentVersionId,
                "",
                List.of(),
                kind,
                "tester",
                id,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                NOW
        );
    }

    private static RecoveryDraft draft(List<StoredBlockChange> changes) {
        return new RecoveryDraft(
                PROJECT_ID.toString(),
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                NOW,
                NOW,
                changes
        );
    }

    private static StoredBlockChange change(int x) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 0),
                StatePayload.air(),
                new StatePayload(blockState("minecraft:stone"), null)
        );
    }

    private static CompoundTag blockState(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
