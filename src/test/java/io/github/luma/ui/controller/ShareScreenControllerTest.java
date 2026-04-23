package io.github.luma.ui.controller;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.HistoryPackageImportResult;
import io.github.luma.domain.model.ImportedHistoryProjectSummary;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.ProjectArchiveExportResult;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VariantMergeApplyRequest;
import io.github.luma.domain.model.VariantMergePlan;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShareScreenControllerTest {

    @Test
    void loadStateDoesNotTriggerMergePreviewUntilRequested() {
        FakeQuery query = new FakeQuery();
        FakeActions actions = new FakeActions();
        ShareScreenController controller = new ShareScreenController(query, actions);

        var state = controller.loadState("Tower", "luma.status.share_ready");

        assertEquals(1, query.projectLoads);
        assertEquals(1, query.importedProjectLoads);
        assertEquals(0, actions.previewCalls);
        assertEquals("Tower - Shared Roof pass", state.importedProjects().getFirst().projectName());

        controller.previewMerge("Tower", "Tower - Shared Roof pass", "roof-pass", "main");
        assertEquals(1, actions.previewCalls);
    }

    private static final class FakeQuery implements ShareScreenController.Query {

        private int projectLoads;
        private int importedProjectLoads;

        @Override
        public boolean hasSingleplayerServer() {
            return true;
        }

        @Override
        public BuildProject loadProject(String projectName) {
            this.projectLoads += 1;
            return new BuildProject(
                    BuildProject.CURRENT_SCHEMA_VERSION,
                    UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    projectName,
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

        @Override
        public List<ProjectVariant> loadVariants(String projectName) {
            return List.of(new ProjectVariant("main", "Main", "v0001", "v0002", true, instant(0)));
        }

        @Override
        public List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) {
            return List.of(version("v0002", 60), version("v0001", 0));
        }

        @Override
        public List<ImportedHistoryProjectSummary> loadImportedProjects(String projectName) {
            this.importedProjectLoads += 1;
            return List.of(new ImportedHistoryProjectSummary(
                    "Tower - Shared Roof pass",
                    "roof-pass",
                    "Roof pass",
                    "v0002",
                    instant(120)
            ));
        }

        @Override
        public OperationSnapshot loadOperationSnapshot(BuildProject project) {
            return new OperationSnapshot(
                    new OperationHandle("op", project.id().toString(), "import", instant(120), false),
                    OperationStage.COMPLETED,
                    new OperationProgress(1, 1, "packages"),
                    "Imported",
                    instant(120)
            );
        }
    }

    private static final class FakeActions implements ShareScreenController.Actions {

        private int previewCalls;

        @Override
        public ProjectArchiveExportResult exportVariantPackage(String projectName, String variantId) {
            return null;
        }

        @Override
        public HistoryPackageImportResult importVariantPackage(String projectName, String archivePath) {
            return null;
        }

        @Override
        public VariantMergePlan previewMerge(String targetProjectName, String sourceProjectName, String sourceVariantId, String targetVariantId) {
            this.previewCalls += 1;
            return null;
        }

        @Override
        public void startMerge(VariantMergeApplyRequest request) {
        }

        @Override
        public void showConflictZoneOverlay(String sourceProjectName, String sourceVariantId, String targetVariantId, io.github.luma.domain.model.MergeConflictZone zone) {
        }
    }

    private static ProjectVersion version(String id, long offsetSeconds) {
        return new ProjectVersion(
                id,
                "66666666-6666-6666-6666-666666666666",
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
