package io.github.luma.ui.controller;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationProgress;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.domain.model.OperationStage;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenOperationStateSupportTest {

    @Test
    void activeOperationBlocksMutationsButCompletedOneDoesNot() {
        OperationSnapshot active = snapshot(OperationStage.APPLYING, "Applying");
        OperationSnapshot completed = snapshot(OperationStage.COMPLETED, "Done");

        assertTrue(ScreenOperationStateSupport.blocksMutationActions(active));
        assertFalse(ScreenOperationStateSupport.blocksMutationActions(completed));
        assertFalse(ScreenOperationStateSupport.blocksMutationActions(null));
    }

    @Test
    void transientOperationStatusResetsAfterSnapshotDisappears() {
        assertEquals(
                "luma.status.project_ready",
                ScreenOperationStateSupport.normalizeStatusKey("luma.status.save_started", null, "luma.status.project_ready")
        );
        assertEquals(
                "luma.status.save_started",
                ScreenOperationStateSupport.normalizeStatusKey(
                        "luma.status.save_started",
                        snapshot(OperationStage.PREPARING, "Preparing"),
                        "luma.status.project_ready"
                )
        );
        assertEquals(
                "luma.status.variant_switched",
                ScreenOperationStateSupport.normalizeStatusKey("luma.status.variant_switched", null, "luma.status.project_ready")
        );
    }

    private static OperationSnapshot snapshot(OperationStage stage, String detail) {
        return new OperationSnapshot(
                new OperationHandle("op", "project", "save", Instant.parse("2026-04-23T08:00:00Z"), false),
                stage,
                new OperationProgress(5, 10, "blocks"),
                detail,
                Instant.parse("2026-04-23T08:00:05Z")
        );
    }
}
