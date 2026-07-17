package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ClientVersionPreviewCaptureTest {
    @Test
    void capturesWorldBeforeUiAndPublishesOnlySuccessfulSaveHeads() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/ClientVersionPreviewCapture.java"));

        assertTrue(source.contains("WorldRenderEvents.END_MAIN"));
        assertTrue(source.contains("Screenshot.takeScreenshot"));
        assertTrue(source.contains("case SUCCEEDED"));
        assertTrue(source.contains("case FAILED, CANCELLED, RETURNED, DEGRADED"));
        assertTrue(source.contains("WIDTH = 320"));
        assertTrue(source.contains("HEIGHT = 180"));
        assertTrue(source.contains("MAX_PENDING = 4"));
    }
}
