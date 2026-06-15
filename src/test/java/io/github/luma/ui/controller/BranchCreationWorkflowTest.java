package io.github.luma.ui.controller;

import io.github.luma.domain.model.ProjectVariant;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BranchCreationWorkflowTest {

    @Test
    void createAndSwitchUsesCreatedBranchAsSwitchTarget() throws Exception {
        List<String> calls = new ArrayList<>();
        BranchCreationWorkflow workflow = new BranchCreationWorkflow(
                (projectName, branchName, baseVersionId) -> {
                    calls.add("create:" + projectName + ":" + branchName + ":" + baseVersionId);
                    return new ProjectVariant("feature-a", "Feature A", "v0002", "v0002", false, Instant.EPOCH);
                },
                (projectName, branchId) -> calls.add("switch:" + projectName + ":" + branchId)
        );

        ProjectVariant created = workflow.createAndSwitch("Tower", "Feature A", "v0002");

        assertEquals("feature-a", created.id());
        assertEquals(List.of(
                "create:Tower:Feature A:v0002",
                "switch:Tower:feature-a"
        ), calls);
    }
}
