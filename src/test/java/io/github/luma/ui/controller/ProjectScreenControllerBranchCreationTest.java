package io.github.luma.ui.controller;

import io.github.luma.domain.model.ProjectVariant;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectScreenControllerBranchCreationTest {

    @Test
    void createAndSwitchVariantReportsSwitchStatusAfterWorkflowSucceeds() {
        BranchCreationWorkflow workflow = new BranchCreationWorkflow(
                (projectName, branchName, baseVersionId) ->
                        new ProjectVariant("feature-a", branchName, baseVersionId, baseVersionId, false, Instant.EPOCH),
                (projectName, branchId) -> {
                }
        );
        ProjectScreenController controller = new ProjectScreenController(workflow);

        BranchCreationResult result = controller.createAndSwitchVariant("Tower", "Feature A", "v0002");

        assertEquals("luma.status.variant_switched", result.statusKey());
        assertEquals("feature-a", result.variantId());
    }
}
