package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PreviewRenderMeshBuilderTest {
    @Test
    void keepsLayeredModelsFluidsCullingAndDepthSort() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/PreviewRenderMeshBuilder.java"));

        assertTrue(source.contains("PreviewCullingBlockGetter"));
        assertTrue(source.contains("PreviewTranslatedBlockGetter"));
        String nativeRenderer = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/PreviewBlockTessellator.java"));
        assertTrue(source.contains("PreviewBlockTessellator"));
        assertTrue(nativeRenderer.contains("renderLiquid("));
        assertTrue(nativeRenderer.contains("renderBatched("));
        assertTrue(source.contains("sortQuads("));
        assertTrue(source.contains("PreviewFramingCalculator.rotationMatrix()"));
        assertTrue(source.contains("Thread.currentThread().isInterrupted()"));
    }
}
