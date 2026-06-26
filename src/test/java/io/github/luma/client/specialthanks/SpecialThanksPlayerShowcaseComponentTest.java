package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecialThanksPlayerShowcaseComponentTest {

    @Test
    void showcaseUsesVanillaFullBodyRendererWithMotion() throws IOException {
        Path sourceFile = Path.of("src/client/java/io/github/luma/client/specialthanks/SpecialThanksPlayerShowcaseComponent.java");

        assertTrue(Files.exists(sourceFile));

        String source = Files.readString(sourceFile);
        assertTrue(source.contains("PlayerModel"));
        assertTrue(source.contains("ModelLayers.PLAYER"));
        assertTrue(source.contains("ModelLayers.PLAYER_SLIM"));
        assertTrue(source.contains("SpecialThanksClientCache.getInstance()"));
        assertTrue(source.contains(".skinFor("));
        assertTrue(source.contains("submitSkinRenderState"));
        assertTrue(source.contains("PlayerModelType.SLIM"));
        assertTrue(source.contains("Math.sin"));
        assertTrue(source.contains("rotationY"));
    }
}
