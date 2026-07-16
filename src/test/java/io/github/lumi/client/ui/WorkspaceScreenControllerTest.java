package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockBox;
import java.util.ArrayList;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WorkspaceScreenControllerTest {
    @Test
    void validatesAndSendsWholeOrBoundedWorkspace() {
        var requests = new ArrayList<String>();
        WorkspaceScreenController controller = new WorkspaceScreenController(
                (name, bounds) -> requests.add(
                        name + ":" + bounds.map(BlockBox::minX).orElse(null)));

        assertEquals("luma.status.project_invalid_name",
                controller.create(" ", Optional.empty()).error());
        assertTrue(controller.create("  Whole world  ", Optional.empty()).accepted());
        assertTrue(controller.create(
                "  Castle  ", Optional.of(new BlockBox(4, 5, 6, 7, 8, 9))).accepted());

        assertEquals(java.util.List.of("Whole world:null", "Castle:4"), requests);
    }

    @Test
    void reportsRejectedCreateIntent() {
        WorkspaceScreenController controller = new WorkspaceScreenController((name, bounds) -> {
            throw new IllegalStateException("Workspace already exists");
        });

        WorkspaceScreenController.Submission result =
                controller.create("Castle", Optional.empty());

        assertEquals(false, result.accepted());
        assertEquals("Workspace already exists", result.error());
    }
}
