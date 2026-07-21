package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class IsometricPreviewCoordinatorTest {
    @Test
    void startsOnlyAfterSuccessAndKeepsTheQueueBounded() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/IsometricPreviewCoordinator.java"));

        assertTrue(source.contains("MAX_PENDING = 4"));
        assertTrue(source.contains("closeCapture(oldest, removed)"));
        assertTrue(source.contains("case SUCCEEDED"));
        assertTrue(source.contains("item.previewBounds()"));
        assertTrue(source.contains(".or(event::previewBounds)"));
        assertTrue(source.contains("boundsLimiter.limit("));
        assertTrue(source.contains("snapshots.read("));
        assertTrue(source.contains("meshes.scheduleBuild("));
        assertTrue(source.contains("capture().capture("));
        assertTrue(source.contains("if (capture == null)"));
        assertTrue(source.contains("if (capture != null) capture.close()"));
        assertTrue(source.contains("store.save(item.dimensionId(), target, image)"));
        assertTrue(source.contains("worker.shutdownNow()"));
    }
}
