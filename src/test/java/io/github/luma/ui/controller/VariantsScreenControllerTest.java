package io.github.luma.ui.controller;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.WorkZone;
import io.github.luma.domain.service.ProjectVersionVisibility;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VariantsScreenControllerTest {

    @Test
    void loadStateUsesSummaryQueriesAndKeepsVersionsSorted() {
        FakeQuery query = new FakeQuery();
        VariantsScreenController controller = new VariantsScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready");

        assertEquals(1, query.projectLoads);
        assertEquals(1, query.variantLoads);
        assertEquals(1, query.activeZoneLoads);
        assertEquals(1, query.versionLoads);
        assertEquals(0, query.zoneVersionLoads);
        assertEquals(1, query.operationLoads);
        assertEquals(List.of("v0003", "v0002", "v0001"), state.versions().stream().map(ProjectVersion::id).toList());
    }

    @Test
    void loadStateUsesZoneHistoryWhenAZoneIsActive() {
        FakeQuery query = new FakeQuery();
        query.activeZone = new WorkZone("zone-a", "project", "Zone A", 0x55CCFF, List.of(), "tester", instant(0), instant(0));
        VariantsScreenController controller = new VariantsScreenController(query);

        var state = controller.loadState("Tower", "luma.status.project_ready");

        assertEquals(0, query.versionLoads);
        assertEquals(1, query.zoneVersionLoads);
        assertEquals(query.activeZone, state.activeZone());
        assertEquals(List.of("zone-a-v2", "zone-a-v1"), state.versions().stream().map(ProjectVersion::id).toList());
    }

    private static final class FakeQuery implements VariantsScreenController.Query {

        private int projectLoads;
        private int variantLoads;
        private int versionLoads;
        private int zoneVersionLoads;
        private int activeZoneLoads;
        private int operationLoads;
        private WorkZone activeZone;

        @Override
        public boolean hasSingleplayerServer() {
            return true;
        }

        @Override
        public BuildProject loadProject(String projectName) {
            this.projectLoads += 1;
            return new BuildProject(
                    BuildProject.CURRENT_SCHEMA_VERSION,
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
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
            this.variantLoads += 1;
            return List.of(new ProjectVariant("main", "Main", "v0001", "v0003", true, instant(0)));
        }

        @Override
        public List<ProjectVersion> loadVersions(String projectName, List<ProjectVariant> variants) {
            this.versionLoads += 1;
            return List.of(version("v0002", 60), version("v0001", 0), version("v0003", 120));
        }

        @Override
        public List<ProjectVersion> loadWorkZoneVersions(String projectName) {
            this.zoneVersionLoads += 1;
            return List.of(
                    zoneVersion("zone-a-v1", "zone-a", 10),
                    zoneVersion("zone-b-v1", "zone-b", 20),
                    zoneVersion("zone-a-v2", "zone-a", 30)
            );
        }

        @Override
        public WorkZone loadActiveZone(String projectName) {
            this.activeZoneLoads += 1;
            return this.activeZone;
        }

        @Override
        public OperationSnapshot loadOperationSnapshot(BuildProject project) {
            this.operationLoads += 1;
            return new OperationSnapshot(
                    new OperationHandle("op", project.id().toString(), "restore", instant(120), false),
                    OperationStage.COMPLETED,
                    new OperationProgress(10, 10, "blocks"),
                    "Completed",
                    instant(120)
            );
        }
    }

    private static ProjectVersion version(String id, long offsetSeconds) {
        return new ProjectVersion(
                id,
                "22222222-2222-2222-2222-222222222222",
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

    private static ProjectVersion zoneVersion(String id, String zoneId, long offsetSeconds) {
        return new ProjectVersion(
                id,
                "22222222-2222-2222-2222-222222222222",
                "main",
                "",
                "",
                List.of(),
                io.github.luma.domain.model.VersionKind.MANUAL,
                "tester",
                id,
                io.github.luma.domain.model.ChangeStats.empty(),
                io.github.luma.domain.model.PreviewInfo.none(),
                io.github.luma.domain.model.ExternalSourceInfo.external(
                        "LUMI",
                        "work-zone",
                        "Work Zone Save",
                        "tester",
                        null,
                        false,
                        false,
                        Map.of(ProjectVersionVisibility.WORK_ZONE_ID_METADATA, zoneId)
                ),
                instant(offsetSeconds)
        );
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }
}
