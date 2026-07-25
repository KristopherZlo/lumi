package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientVersionPreviewCaptureTest {
    @Test
    void delegatesSaveEventsToTheIsometricCoordinator() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/ClientVersionPreviewCapture.java"));

        assertTrue(source.contains("WorldRenderEvents.END_MAIN"));
        assertTrue(source.contains("IsometricPreviewCoordinator"));
        assertTrue(source.contains("coordinator.request(requestId, snapshot)"));
        assertTrue(source.contains("coordinator.accept(event)"));
        assertTrue(source.contains("coordinator.tick()"));
        assertTrue(source.contains("client.execute(coordinator::clear)"));
        assertFalse(source.contains("Screenshot"));
        assertFalse(source.contains("getMainRenderTarget"));
    }
}
