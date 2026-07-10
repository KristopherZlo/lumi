package io.github.luma.ui.overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentChangesOverlayCoordinatorTest {

    @Test
    void recentOverlayUsesFullPreviewSnapshotsWithoutDrainingStabilization() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/ui/overlay/RecentChangesOverlayCoordinator.java"
        ));

        assertTrue(!source.contains("drainUndoRedoStabilization("));
        assertTrue(!source.contains("MAX_PREVIEW_ENTRIES_PER_ACTION"));
        assertTrue(source.contains("recentUndoPreviewActionsSnapshot("));
        assertTrue(source.contains("recentRedoPreviewActionsSnapshot("));
        assertTrue(source.contains("recentUndoRedoPreviewActionsSnapshot("));
        assertTrue(source.contains("RecentChangesOverlayRenderer.discard(prepared);"));

        int pendingIndex = source.indexOf("RecentChangesPreviewSession.PreviewKey pending = this.pendingPreview;");
        int requestIndex = source.indexOf("this.previewSession.request(");
        assertTrue(pendingIndex >= 0);
        assertTrue(pendingIndex < requestIndex);
    }
}
