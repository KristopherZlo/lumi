package io.github.luma.ui.controller;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.PartialRestoreMode;
import io.github.luma.domain.model.PartialRestorePlanSummary;
import io.github.luma.domain.model.PartialRestoreRegionSource;
import io.github.luma.domain.model.RestorePlanMode;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectScreenControllerTest {

    @Test
    void variantFailuresUseSpecificStatusKeys() {
        assertEquals(
                "luma.status.variant_name_required",
                ProjectScreenController.variantFailureStatus(new IllegalArgumentException("Variant name is required"))
        );
        assertEquals(
                "luma.status.variant_already_exists",
                ProjectScreenController.variantFailureStatus(new IllegalArgumentException("Variant already exists: feature"))
        );
        assertEquals(
                "luma.status.variant_switch_requires_saved_draft",
                ProjectScreenController.variantFailureStatus(new IllegalArgumentException(
                        "Discard or save the current recovery draft before switching variants"
                ))
        );
    }

    @Test
    void variantFailuresExposeAccessAndBusyStatuses() {
        assertEquals(
                "luma.status.admin_required",
                ProjectScreenController.variantFailureStatus(new IllegalStateException(
                        "Lumi requires admin permissions or cheats enabled"
                ))
        );
        assertEquals(
                "luma.status.world_operation_busy",
                ProjectScreenController.variantFailureStatus(new IllegalStateException(
                        "Another world operation is already running"
                ))
        );
    }

    @Test
    void historyEditFailuresExposeBranchDeleteStatuses() {
        assertEquals(
                "luma.status.variant_delete_blocked",
                ProjectScreenController.historyEditFailureStatus(new IllegalArgumentException("Main branch cannot be deleted"))
        );
        assertEquals(
                "luma.status.variant_delete_blocked",
                ProjectScreenController.historyEditFailureStatus(new IllegalArgumentException("Active branch cannot be deleted"))
        );
        assertEquals(
                "luma.status.save_name_required",
                ProjectScreenController.historyEditFailureStatus(new IllegalArgumentException("Save name is required"))
        );
        assertEquals(
                "luma.status.version_delete_blocked",
                ProjectScreenController.historyEditFailureStatus(new IllegalArgumentException("Only leaf saves can be deleted"))
        );
    }

    @Test
    void mergeFailuresExposeSpecificStatuses() {
        assertEquals(
                "luma.status.merge_requires_saved_draft",
                ProjectScreenController.mergeFailureStatus(new IllegalArgumentException(
                        "Discard or save the current recovery draft before merging branches"
                ))
        );
        assertEquals(
                "luma.status.merge_no_changes",
                ProjectScreenController.mergeFailureStatus(new IllegalArgumentException("Source branch does not add any new changes"))
        );
        assertEquals(
                "luma.status.merge_conflicts_found",
                ProjectScreenController.mergeFailureStatus(new IllegalArgumentException(
                        "Merge conflicts must be resolved before applying the merge"
                ))
        );
    }

    @Test
    void partialRestoreStatusReportsSelectedNoOp() {
        assertEquals(
                "luma.status.partial_restore_no_changes_selected",
                ProjectScreenController.partialRestoreStatus(noOpSummary(PartialRestoreMode.SELECTED_AREA))
        );
    }

    @Test
    void partialRestoreStatusReportsOutsideSelectionNoOp() {
        assertEquals(
                "luma.status.partial_restore_no_changes_outside_selection",
                ProjectScreenController.partialRestoreStatus(noOpSummary(PartialRestoreMode.OUTSIDE_SELECTED_AREA))
        );
    }

    @Test
    void partialRestoreStatusReportsStartedWhenSummaryHasChanges() {
        assertEquals(
                "luma.status.partial_restore_started",
                ProjectScreenController.partialRestoreStatus(summary(PartialRestoreMode.SELECTED_AREA, 1, 0))
        );
        assertEquals(
                "luma.status.partial_restore_started",
                ProjectScreenController.partialRestoreStatus(summary(PartialRestoreMode.OUTSIDE_SELECTED_AREA, 0, 1))
        );
    }

    private static PartialRestorePlanSummary noOpSummary(PartialRestoreMode mode) {
        return summary(mode, 0, 0);
    }

    private static PartialRestorePlanSummary summary(PartialRestoreMode mode, int changedBlocks, int changedEntities) {
        return new PartialRestorePlanSummary(
                RestorePlanMode.NO_OP,
                new Bounds3i(new BlockPoint(0, 64, 0), new BlockPoint(1, 65, 1)),
                mode,
                PartialRestoreRegionSource.LUMI_REGION,
                List.of(),
                "main",
                "v0001",
                "v0002",
                changedBlocks,
                changedEntities
        );
    }
}
