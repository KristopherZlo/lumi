package io.github.luma.ui.controller;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.BuildProject;
import io.github.luma.domain.model.ChangeStats;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import io.github.luma.domain.model.PendingChangeSummary;
import io.github.luma.domain.model.PreviewInfo;
import io.github.luma.domain.model.ProjectSettings;
import io.github.luma.domain.model.ProjectVariant;
import io.github.luma.domain.model.ProjectVersion;
import io.github.luma.domain.model.VersionKind;
import io.github.luma.domain.model.ExternalSourceInfo;
import io.github.luma.ui.state.ProjectHomeViewState;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchCreationDialogStateFactoryTest {

    @Test
    void createsDialogStateForSelectedHistoryVersion() {
        BranchCreationDialogStateFactory factory = new BranchCreationDialogStateFactory();
        ProjectHomeViewState home = homeState(null);

        var state = factory.create(home, "v0002", "roof-pass");

        assertTrue(state.visible());
        assertEquals("v0002", state.baseVersion().id());
        assertEquals("Main", state.baseVariantName());
        assertEquals("Second save", state.baseVersionName());
        assertEquals("roof-pass", state.branchName());
        assertTrue(state.canCreate());
    }

    @Test
    void createsDialogStateFromLoadedVersionLists() {
        BranchCreationDialogStateFactory factory = new BranchCreationDialogStateFactory();

        var state = factory.create(
                List.of(version("v0002", "Second save", "v0001", 60)),
                List.of(new ProjectVariant("main", "Main", "v0001", "v0002", true, instant(0))),
                null,
                "v0002",
                "roof-pass"
        );

        assertTrue(state.visible());
        assertEquals("Second save", state.baseVersionName());
        assertEquals("Main", state.baseVariantName());
        assertTrue(state.canCreate());
    }

    @Test
    void disablesCreateWhileMutationOperationIsActive() {
        BranchCreationDialogStateFactory factory = new BranchCreationDialogStateFactory();
        ProjectHomeViewState home = homeState(new OperationSnapshot(
                new OperationHandle("op", "11111111-1111-1111-1111-111111111111", "restore", instant(180), false),
                OperationStage.APPLYING,
                OperationProgress.empty("blocks"),
                "Restoring",
                instant(180)
        ));

        var state = factory.create(home, "v0002", "roof-pass");

        assertTrue(state.visible());
        assertFalse(state.canCreate());
    }

    @Test
    void hidesDialogWhenSelectedVersionIsMissing() {
        BranchCreationDialogStateFactory factory = new BranchCreationDialogStateFactory();

        var state = factory.create(homeState(null), "missing", "roof-pass");

        assertFalse(state.visible());
        assertFalse(state.canCreate());
    }

    private static ProjectHomeViewState homeState(OperationSnapshot operationSnapshot) {
        return new ProjectHomeViewState(
                project(),
                List.of(version("v0002", "Second save", "v0001", 60), version("v0001", "Root", "", 0)),
                List.of(new ProjectVariant("main", "Main", "v0001", "v0002", true, instant(0))),
                PendingChangeSummary.empty(),
                false,
                operationSnapshot,
                null,
                "luma.status.project_ready"
        );
    }

    private static BuildProject project() {
        return new BuildProject(
                BuildProject.CURRENT_SCHEMA_VERSION,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
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

    private static ProjectVersion version(String id, String message, String parentId, long offsetSeconds) {
        return new ProjectVersion(
                id,
                "11111111-1111-1111-1111-111111111111",
                "main",
                parentId,
                "",
                List.of(),
                VersionKind.MANUAL,
                "tester",
                message,
                ChangeStats.empty(),
                PreviewInfo.none(),
                ExternalSourceInfo.manual(),
                instant(offsetSeconds)
        );
    }

    private static Instant instant(long seconds) {
        return Instant.parse("2026-04-23T08:00:00Z").plusSeconds(seconds);
    }
}
