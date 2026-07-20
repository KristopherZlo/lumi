package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TexturedPreviewCaptureServiceTest {
    @Test
    void isolatesWorldFogWhileRenderingThePreview() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/TexturedPreviewCaptureService.java"));

        assertTrue(source.contains("RenderSystem.getShaderFog()"));
        assertTrue(source.contains("FogRenderer.FogMode.NONE"));
        assertTrue(source.contains("RenderSystem.setShaderFog(previousFog)"));
        assertTrue(source.contains("previewFog.close()"));
    }
}
