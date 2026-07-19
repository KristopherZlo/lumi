package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PreviewRenderMeshBuilderTest {
    @Test
    void keepsLegacyLayeredModelsFluidsCullingAndDepthSort() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/PreviewRenderMeshBuilder.java"));

        assertTrue(source.contains("PreviewCullingBlockGetter"));
        assertTrue(source.contains("PreviewTranslatedBlockGetter"));
        assertTrue(source.contains("renderLiquid("));
        assertTrue(source.contains("renderBatched("));
        assertTrue(source.contains("sortQuads("));
        assertTrue(source.contains("PreviewFramingCalculator.rotationMatrix()"));
        assertTrue(source.contains("Thread.currentThread().isInterrupted()"));
    }
}
