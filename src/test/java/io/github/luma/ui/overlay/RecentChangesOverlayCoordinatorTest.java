package io.github.luma.ui.overlay;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentChangesOverlayCoordinatorTest {

    @Test
    void recentOverlayDrainsStabilizationBeforeSnapshottingUndoHistory() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/ui/overlay/RecentChangesOverlayCoordinator.java"
        ));

        int drainIndex = source.indexOf("this.captureManager.drainUndoRedoStabilization(");
        int undoSnapshotIndex = source.indexOf("this.historyManager.recentUndoActionsSnapshot(");
        int bothSnapshotIndex = source.indexOf("this.historyManager.recentUndoRedoActionsSnapshot(");

        assertTrue(drainIndex >= 0);
        assertTrue(drainIndex < undoSnapshotIndex);
        assertTrue(drainIndex < bothSnapshotIndex);
    }
}
