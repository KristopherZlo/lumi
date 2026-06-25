package io.github.luma.ui.controller;

import io.github.luma.domain.model.ProjectVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class QuickSaveScreenControllerTest {

    @Test
    void startsCurrentWorkspaceSaveWithTrimmedName() {
        FakeQuery query = new FakeQuery();
        QuickSaveScreenController controller = new QuickSaveScreenController(query);

        assertEquals("luma.status.save_started", controller.saveCurrentWorkspace("  Before tower roof  "));

        assertEquals("Before tower roof", query.savedMessage);
    }

    @Test
    void passesTagsToCurrentWorkspaceSave() {
        FakeQuery query = new FakeQuery();
        QuickSaveScreenController controller = new QuickSaveScreenController(query);

        assertEquals("luma.status.save_started", controller.saveCurrentWorkspace("Checkpoint", List.of("roof")));

        assertEquals(List.of("roof"), query.savedTags);
    }

    @Test
    void exposesActiveZoneNameForZoneSaveModalCopy() {
        FakeQuery query = new FakeQuery();
        query.activeZoneName = "Tower roof";
        QuickSaveScreenController controller = new QuickSaveScreenController(query);

        assertEquals("Tower roof", controller.activeZoneName());
    }

    @Test
    void allowsBlankNameLikeTheMainSaveDialog() {
        FakeQuery query = new FakeQuery();
        QuickSaveScreenController controller = new QuickSaveScreenController(query);

        assertEquals("luma.status.save_started", controller.saveCurrentWorkspace(" "));

        assertEquals("", query.savedMessage);
    }

    @Test
    void reportsSingleplayerOnlyBeforeSaving() {
        FakeQuery query = new FakeQuery();
        query.singleplayer = false;
        QuickSaveScreenController controller = new QuickSaveScreenController(query);

        assertEquals("luma.status.singleplayer_only", controller.saveCurrentWorkspace("Checkpoint"));

        assertFalse(query.saveCalled);
    }

    @Test
    void mapsNoPendingChangesToBuilderStatus() {
        FakeQuery query = new FakeQuery();
        query.failure = new IllegalArgumentException("No pending tracked changes for Tower");
        QuickSaveScreenController controller = new QuickSaveScreenController(query);

        assertEquals("luma.status.no_changes_to_save", controller.saveCurrentWorkspace("Checkpoint"));
    }

    @Test
    void mapsAdminRejectionSeparatelyFromBusyOperations() {
        FakeQuery query = new FakeQuery();
        query.failure = new IllegalStateException("Lumi requires admin permissions or cheats enabled");
        QuickSaveScreenController controller = new QuickSaveScreenController(query);

        assertEquals("luma.status.admin_required", controller.saveCurrentWorkspace("Checkpoint"));

        query.failure = new IllegalStateException("Another world operation is already running");
        assertEquals("luma.status.world_operation_busy", controller.saveCurrentWorkspace("Checkpoint"));
    }

    private static final class FakeQuery implements QuickSaveScreenController.Query {

        private boolean singleplayer = true;
        private RuntimeException failure;
        private boolean saveCalled;
        private String savedMessage = "";
        private String activeZoneName = "";

        @Override
        public boolean hasSingleplayerServer() {
            return this.singleplayer;
        }

        @Override
        public void saveCurrentWorkspace(String message, List<String> tags) {
            this.saveCalled = true;
            if (this.failure != null) {
                throw this.failure;
            }
            this.savedMessage = message;
            this.savedTags = tags;
        }

        @Override
        public List<ProjectVersion> currentWorkspaceVersions() {
            return List.of();
        }

        @Override
        public String activeZoneName() {
            return this.activeZoneName;
        }

        private List<String> savedTags = List.of();
    }
}
