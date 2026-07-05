package io.github.luma.ui.controller;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.ProjectIntegrityReport;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.RecoveryDraft;
import io.github.luma.domain.model.RecoveryJournalEntry;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.EntityPayload;
import io.github.luma.domain.model.StoredEntityChange;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.model.WorkZoneState;
import io.github.luma.domain.model.WorldMutationSource;
import io.github.luma.domain.service.ProjectVersionVisibility;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import io.github.luma.integration.common.IntegrationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectHomeScreenControllerTest {

    @Test
    void loadStateSkipsAdvancedQueriesUntilExpanded() {
        FakeQuery query = new FakeQuery();
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var collapsed = controller.loadState("Tower", "luma.status.project_ready", false);
        assertNull(collapsed.advanced());
        assertEquals(0, query.totalIntegrityLoads);
        assertEquals(0, query.totalJournalLoads);
        assertEquals(0, query.totalIntegrationLoads);
        assertEquals("v0002", collapsed.versions().getFirst().id());
        assertFalse(collapsed.hasRecoveryDraft());

        var expanded = controller.loadState("Tower", "luma.status.project_ready", true);

        assertTrue(expanded.advanced() != null);
        assertEquals(1, query.totalIntegrityLoads);
        assertEquals(1, query.totalJournalLoads);
        assertEquals(1, query.totalIntegrationLoads);
    }

    @Test
    void currentRunDraftDoesNotShowRecoveryAction() {
        FakeQuery query = new FakeQuery();
        query.draft = nonEmptyDraft();
        query.interruptedDraft = false;
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready", false);

        assertFalse(state.hasRecoveryDraft());
        assertEquals(1, state.pendingChanges().addedBlocks());
    }

    @Test
    void currentRunEntityDraftShowsPendingChanges() {
        FakeQuery query = new FakeQuery();
        query.draft = entityOnlyDraft();
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready", false);

        assertFalse(state.pendingChanges().isEmpty());
        assertEquals(1, state.pendingChanges().total());
    }

    @Test
    void interruptedDraftShowsRecoveryAction() {
        FakeQuery query = new FakeQuery();
        query.draft = nonEmptyDraft();
        query.interruptedDraft = true;
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready", false);

        assertTrue(state.hasRecoveryDraft());
    }

    @Test
    void loadStateHidesActiveZoneCommitsFromGlobalHistoryByDefault() {
        FakeQuery query = new FakeQuery();
        query.versions = List.of(
                version("v0001", 0),
                version("v0002", 60, "zone-a"),
                version("v0003", 120)
        );
        query.workZones = WorkZoneState.empty().withZones(List.of(zone("zone-a")));
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready", false);

        assertEquals(List.of("v0003", "v0001"), state.versions().stream().map(ProjectVersion::id).toList());
    }

    @Test
    void loadStateShowsHiddenCommitsWhenProjectSettingIsEnabled() {
        FakeQuery query = new FakeQuery();
        query.settings = new ProjectSettings(false, 10, 5, 10, 0.20D, true, true, false, false, 512, true, true);
        query.versions = List.of(
                version("v0001", 0),
                version("v0002", 60, "zone-a"),
                version("v0003", 120)
        );
        query.workZones = WorkZoneState.empty().withZones(List.of(zone("zone-a")));
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready", false);

        assertEquals(List.of("v0003", "v0002", "v0001"), state.versions().stream().map(ProjectVersion::id).toList());
    }

    @Test
    void loadStateExposesZoneColorsForShownHiddenCommits() {
        FakeQuery query = new FakeQuery();
        query.settings = new ProjectSettings(false, 10, 5, 10, 0.20D, true, true, false, false, 512, true, true);
        query.versions = List.of(
                version("v0001", 0),
                version("v0002", 60, "zone-a")
        );
        query.workZones = WorkZoneState.empty().withZones(List.of(zone("zone-a", 0x55CCFF)));
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready", false);

        assertEquals(0x55CCFF, state.zoneColor(state.versions().getFirst()));
        assertNull(state.zoneColor(state.versions().getLast()));
    }

    @Test
    void loadStateShowsCommitsFromDeletedZonesInGlobalHistory() {
        FakeQuery query = new FakeQuery();
        query.versions = List.of(
                version("v0001", 0),
                version("v0002", 60, "zone-deleted"),
                version("v0003", 120)
        );
        ProjectHomeScreenController controller = new ProjectHomeScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready", false);

        assertEquals(List.of("v0003", "v0002", "v0001"), state.versions().stream().map(ProjectVersion::id).toList());
    }

    private static final class FakeQuery implements ProjectHomeScreenController.Query {

        private int totalIntegrityLoads;
        private int totalJournalLoads;
        private int totalIntegrationLoads;
        private RecoveryDraft draft;
        private boolean interruptedDraft;
        private ProjectSettings settings = ProjectSettings.defaults();
        private List<ProjectVersion> versions = List.of(version("v0001", 0), version("v0002", 60));
        private WorkZoneState workZones = WorkZoneState.empty();

        @Override
        public boolean hasSingleplayerServer() {
            return true;
        }

        @Override
        public BuildProject loadProject(String projectName) {
            return project(projectName, this.settings);
        }

        @Override
        public List<ProjectVariant> loadVariants(String projectName) {
            return List.of(new ProjectVariant("main", "Main", "v0001", "v0002", true, instant(0)));
        }

        @Override
        public List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) {
            return this.versions;
        }

        @Override
        public WorkZoneState loadWorkZones(String projectName) {
            return this.workZones;
        }

        @Override
        public List<ProjectVersion> loadDeletedVersions(String projectName) {
            return List.of();
        }

        @Override
        public RecoveryDraft loadDraft(String projectName) {
            return this.draft;
        }

        @Override
        public boolean hasInterruptedDraft(String projectName) {
            return this.interruptedDraft;
        }

        @Override
        public List<RecoveryJournalEntry> loadJournal(String projectName) {
            this.totalJournalLoads += 1;
            return List.of(new RecoveryJournalEntry(instant(120), "saved", "Saved", "v0002", "main"));
        }

        @Override
        public ProjectIntegrityReport loadIntegrity(String projectName) {
            this.totalIntegrityLoads += 1;
            return new ProjectIntegrityReport(true, List.of(), List.of());
        }

        @Override
        public List<IntegrationStatus> loadIntegrations() {
            this.totalIntegrationLoads += 1;
            return List.of();
        }

        @Override
        public OperationSnapshot loadOperationSnapshot(BuildProject project) {
            return new OperationSnapshot(
                    new OperationHandle("op", project.id().toString(), "save", instant(120), false),
                    OperationStage.PREPARING,
                    OperationProgress.empty("blocks"),
                    "Preparing save",
                    instant(120)
            );
        }

        @Override
        public boolean hasRestoreReturnPoint(String projectName) {
            return false;
        }
    }

    private static BuildProject project(String name, ProjectSettings settings) {
        return new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                name,
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
                settings,
                false,
                false
        );
    }

    private static ProjectVersion version(String id, long offsetSeconds) {
        return version(id, offsetSeconds, "");
    }

    private static ProjectVersion version(String id, long offsetSeconds, String zoneId) {
        return new ProjectVersion(
                id,
                "11111111-1111-1111-1111-111111111111",
                "main",
                "v0001".equals(id) ? "" : "v0001",
                "",
                List.of(),
                io.github.luma.domain.model.VersionKind.MANUAL,
                "tester",
                id,
                io.github.luma.domain.model.ChangeStats.empty(),
                io.github.luma.domain.model.PreviewInfo.none(),
                zoneId == null || zoneId.isBlank()
                        ? io.github.luma.domain.model.ExternalSourceInfo.manual()
                        : io.github.luma.domain.model.ExternalSourceInfo.external(
                                "MANUAL",
                                "manual",
                                "Manual Save",
                                "",
                                null,
                                false,
                                false,
                                java.util.Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, zoneId)
                        ),
                instant(offsetSeconds)
        );
    }

    private static WorkZone zone(String id) {
        return zone(id, 0xFFFFFF);
    }

    private static WorkZone zone(String id, int color) {
        return new WorkZone(
                id,
                "11111111-1111-1111-1111-111111111111",
                id,
                color,
                List.of(),
                "tester",
                instant(0),
                instant(0)
        );
    }

    private static RecoveryDraft nonEmptyDraft() {
        return new RecoveryDraft(
                "11111111-1111-1111-1111-111111111111",
                "main",
                "v0002",
                "tester",
                WorldMutationSource.PLAYER,
                instant(120),
                instant(121),
                List.of(new StoredBlockChange(
                        new BlockPoint(1, 64, 1),
                        StatePayload.air(),
                        payload("minecraft:stone")
                ))
        );
    }

    private static RecoveryDraft entityOnlyDraft() {
        String entityId = "00000000-0000-0000-0000-000000000201";
        return new RecoveryDraft(
                "11111111-1111-1111-1111-111111111111",
                "main",
                "v0002",
                "tester",
                WorldMutationSource.PLAYER,
                instant(120),
                instant(121),
                List.of(),
                List.of(new StoredEntityChange(
                        entityId,
                        "minecraft:block_display",
                        entity(entityId, 1.0D),
                        entity(entityId, 2.0D)
                ))
        );
    }

    private static StatePayload payload(String blockId) {
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

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }
}
