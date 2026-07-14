package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.PendingRestoreCompletion;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.storage.ProjectLayout;
import io.github.luma.storage.repository.RecoveryRepository;
import io.github.luma.storage.repository.VariantRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationDraftRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TempDir
    Path tempDir;

    private final RecoveryRepository repository = new RecoveryRepository();
    private final OperationDraftRecoveryService service = new OperationDraftRecoveryService(this.repository);

    @Test
    void promotesOperationDraftWhenNoLiveDraftExists() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = project();
        RecoveryDraft operationDraft = draft(
                project.id().toString(),
                "main",
                "v0001",
                List.of(change(1, "minecraft:stone", "minecraft:gold_block")),
                NOW
        );
        this.repository.saveOperationDraft(layout, operationDraft);

        var restored = this.service.restoreInterruptedOperationDraft(layout, project);

        assertTrue(restored.isPresent());
        assertFalse(Files.exists(layout.recoveryOperationDraftFile()));
        assertEquals(
                "minecraft:gold_block",
                this.repository.loadDraft(layout).orElseThrow().changes().getFirst().newValue().blockId()
        );
    }

    @Test
    void mergesCompatibleOperationDraftIntoVisibleLiveDraft() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = project();
        this.repository.saveOperationDraft(layout, draft(
                project.id().toString(),
                "main",
                "v0001",
                List.of(
                        change(1, "minecraft:stone", "minecraft:gold_block"),
                        change(2, "minecraft:dirt", "minecraft:glass")
                ),
                NOW
        ));
        this.repository.saveDraft(layout, draft(
                project.id().toString(),
                "main",
                "v0001",
                List.of(
                        change(1, "minecraft:gold_block", "minecraft:diamond_block"),
                        change(3, "minecraft:air", "minecraft:oak_planks")
                ),
                NOW.plusSeconds(5)
        ));

        RecoveryDraft restored = this.service.restoreInterruptedOperationDraft(layout, project).orElseThrow();

        assertFalse(Files.exists(layout.recoveryOperationDraftFile()));
        assertEquals(3, restored.changes().size());
        assertEquals("minecraft:stone", restored.changes().getFirst().oldValue().blockId());
        assertEquals("minecraft:diamond_block", restored.changes().getFirst().newValue().blockId());
        assertEquals(NOW, restored.startedAt());
        assertEquals(NOW.plusSeconds(5), restored.updatedAt());
    }

    @Test
    void keepsIncompatibleOperationDraftSeparateFromLiveDraft() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = project();
        this.repository.saveOperationDraft(layout, draft(
                project.id().toString(),
                "main",
                "v0001",
                List.of(change(1, "minecraft:stone", "minecraft:gold_block")),
                NOW
        ));
        this.repository.saveDraft(layout, draft(
                project.id().toString(),
                "main",
                "v0002",
                List.of(change(1, "minecraft:gold_block", "minecraft:diamond_block")),
                NOW.plusSeconds(5)
        ));

        var restored = this.service.restoreInterruptedOperationDraft(layout, project);

        assertTrue(restored.isEmpty());
        assertTrue(Files.exists(layout.recoveryOperationDraftFile()));
        assertEquals("v0002", this.repository.loadDraft(layout).orElseThrow().baseVersionId());
    }

    @Test
    void ignoresOperationDraftForAnotherProject() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = project();
        this.repository.saveOperationDraft(layout, draft(
                "22222222-2222-2222-2222-222222222222",
                "main",
                "v0001",
                List.of(change(1, "minecraft:stone", "minecraft:gold_block")),
                NOW
        ));

        var restored = this.service.restoreInterruptedOperationDraft(layout, project);

        assertTrue(restored.isEmpty());
        assertTrue(Files.exists(layout.recoveryOperationDraftFile()));
        assertTrue(this.repository.loadDraft(layout).isEmpty());
    }

    @Test
    void discardsPublishedOperationDraftAndKeepsRebasedLiveDraft() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = project();
        this.repository.saveOperationDraft(layout, draft(
                project.id().toString(), "main", "v0001",
                List.of(change(1, "minecraft:stone", "minecraft:gold_block")), NOW
        ));
        this.repository.saveDraft(layout, draft(
                project.id().toString(), "main", "v0002",
                List.of(change(2, "minecraft:dirt", "minecraft:glass")), NOW.plusSeconds(2)
        ));
        new VariantRepository().save(layout, List.of(ProjectVariant.main("v0002", NOW.plusSeconds(1))));

        var restored = this.service.restoreInterruptedOperationDraft(layout, project);

        assertTrue(restored.isEmpty());
        assertFalse(Files.exists(layout.recoveryOperationDraftFile()));
        assertEquals("v0002", this.repository.loadDraft(layout).orElseThrow().baseVersionId());
    }

    @Test
    void leavesPartialRestoreOperationDraftForRestoreCompletion() throws Exception {
        ProjectLayout layout = ProjectLayout.of(this.tempDir, "Tower");
        BuildProject project = project();
        this.repository.saveOperationDraft(layout, draft(
                project.id().toString(), "main", "v0001",
                List.of(change(1, "minecraft:stone", "minecraft:gold_block")), NOW
        ));
        this.repository.savePendingRestoreCompletion(layout, PendingRestoreCompletion.partial(
                project.id().toString(),
                "main",
                "v0001",
                NOW,
                project.bounds(),
                io.github.luma.domain.model.PartialRestoreMode.SELECTED_AREA
        ));

        var restored = this.service.restoreInterruptedOperationDraft(layout, project);

        assertTrue(restored.isEmpty());
        assertTrue(Files.exists(layout.recoveryOperationDraftFile()));
        assertTrue(this.repository.loadDraft(layout).isEmpty());
    }

    private static BuildProject project() {
        return new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                PROJECT_ID,
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

    private static RecoveryDraft draft(
            String projectId,
            String variantId,
            String baseVersionId,
            List<StoredBlockChange> changes,
            Instant updatedAt
    ) {
        return new RecoveryDraft(
                projectId,
                variantId,
                baseVersionId,
                "tester",
                WorldMutationSource.PLAYER,
                NOW,
                updatedAt,
                changes
        );
    }

    private static StoredBlockChange change(int x, String oldBlockId, String newBlockId) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, x),
                payload(oldBlockId),
                payload(newBlockId)
        );
    }

    private static StatePayload payload(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return new StatePayload(tag, null);
    }
}
