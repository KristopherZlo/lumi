package io.github.luma.ui.controller;

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
}
