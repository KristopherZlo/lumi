package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiPartialRestoreFlowTest {
    @Test
    void legacyControlsUseCorrelatedPreviewAndTokenApply() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiRestoreScreen.java"));
        String networking = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClientNetworking.java"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertTrue(screen.contains("luma.action.use_selected_area"));
        assertTrue(screen.contains("luma.action.preview_partial_restore"));
        assertTrue(screen.contains("luma.action.apply_partial_restore"));
        assertTrue(screen.contains("luma.partial_restore.mode_selected_area"));
        assertTrue(screen.contains("luma.partial_restore.mode_outside_selection"));
        assertTrue(screen.contains("accept(PartialRestorePlanPayload"));
        assertTrue(networking.contains("PartialRestorePlanPayload.TYPE"));
        assertTrue(networking.contains("Kind.RESTORE_AREA_PLAN"));
        assertTrue(networking.contains("Kind.RESTORE_AREA_APPLY"));
        assertFalse(networking.contains("public UUID restoreArea("));
        assertTrue(client.contains("LumiClient::showPartialRestorePlan"));
        assertTrue(client.contains("NETWORKING::previewRestoreArea"));
        assertTrue(client.contains("NETWORKING::applyRestoreArea"));
        assertFalse(client.contains("idle && SELECTION.bounds().isPresent()"));
    }
}
