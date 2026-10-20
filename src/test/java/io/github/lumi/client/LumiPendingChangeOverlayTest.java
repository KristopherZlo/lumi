package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiPendingChangeOverlayTest {
    @Test
    void guiPreviewStaysInItsBranchAndZoneScope() {
        var head = new CommitId(new ObjectId("a".repeat(64)));
        var snapshot = new HistorySnapshotPayload("minecraft:overworld", head, 0, 0, false);
        var scope = new LumiPendingChangeOverlay.PreviewScope(
                null, snapshot.workspaceId(), head, snapshot.branchName(), true);
        assertTrue(scope.matches(null, snapshot, true));
        assertFalse(scope.matches(null, snapshot, false));
        assertFalse(scope.matches(null, null, true));
        assertFalse(new LumiPendingChangeOverlay.PreviewScope(
                null, snapshot.workspaceId(), head, "another-branch", false)
                .matches(null, snapshot, true));
    }

    @Test
    void xrayUsesTheRemappableActionBinding() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiPendingChangeOverlay.java"));

        assertTrue(source.contains(
                "LumiHotkeys.actionModifierDown(client.options.keyMappings)"));
        assertFalse(source.contains("KEY_LALT"));
        assertFalse(source.contains("KEY_RALT"));
        assertTrue(source.contains("refresh.visible() && previewScope != null"));
        assertTrue(source.contains("!previewScope.matches(client.level, snapshot,"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));
        assertFalse(client.contains("showFeedback(\"luma.hotkeys.pending_preview_help\")"));
        assertTrue(client.contains("PENDING_OVERLAY.showPendingPreview()"));
        assertTrue(client.contains("PENDING_OVERLAY.togglePendingPreview().or(COMPARISONS::toggleVisibility)"));
    }
}
