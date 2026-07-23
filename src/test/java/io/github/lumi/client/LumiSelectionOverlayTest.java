package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSelectionOverlayTest {
    @Test
    void rendersTheInclusiveCyanBoxOnlyWhileTheToolIsVisible() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiSelectionOverlay.java"));

        assertEquals(0xff35c6ff, LumiSelectionOverlay.COLOR);
        assertTrue(source.contains("bounds.maxX() + 1.0"));
        assertTrue(source.contains("bounds.maxY() + 1.0"));
        assertTrue(source.contains("bounds.maxZ() + 1.0"));
        assertTrue(source.contains("LumiSelectionTool.held(client)"));
        assertTrue(source.contains("client.screen != null"));
        assertTrue(source.contains("FILL_ALPHA = 42"));
        assertTrue(source.contains("FRAME_ALPHA = 255"));
        assertTrue(source.contains("FRAME_RADIUS = 0.04F"));
        assertTrue(source.contains("renderFrame("));
        assertTrue(!source.contains("ShapeRenderer.renderShape"));
    }
}
