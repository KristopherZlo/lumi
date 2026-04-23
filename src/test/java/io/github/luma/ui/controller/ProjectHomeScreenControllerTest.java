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

    private static final class FakeQuery implements ProjectHomeScreenController.Query {

        private int totalIntegrityLoads;
        private int totalJournalLoads;
        private int totalIntegrationLoads;

        @Override
        public boolean hasSingleplayerServer() {
            return true;
        }

        @Override
        public BuildProject loadProject(String projectName) {
            return project(projectName);
        }

        @Override
        public List<ProjectVariant> loadVariants(String projectName) {
            return List.of(new ProjectVariant("main", "Main", "v0001", "v0002", true, instant(0)));
        }

        @Override
        public List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) {
            return List.of(version("v0001", 0), version("v0002", 60));
        }

        @Override
        public RecoveryDraft loadDraft(String projectName) {
            return null;
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
    }

    private static BuildProject project(String name) {
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
                ProjectSettings.defaults(),
                false,
                false
        );
    }

    private static ProjectVersion version(String id, long offsetSeconds) {
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
                io.github.luma.domain.model.ExternalSourceInfo.manual(),
                instant(offsetSeconds)
        );
    }
    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }
}
