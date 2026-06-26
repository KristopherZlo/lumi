package io.github.luma.client.specialthanks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void secondLayerUsesParentPartTransformOnly() throws IOException {
        String source = Files.readString(Path.of(
                "src/client/java/io/github/luma/client/specialthanks/SpecialThanksPlayerShowcaseComponent.java"
        ));

        assertFalse(source.contains("copyRotation("));
        assertFalse(source.contains("hat.xRot"));
        assertFalse(source.contains("rightSleeve.xRot"));
        assertFalse(source.contains("leftSleeve.xRot"));
        assertFalse(source.contains("rightPants.xRot"));
        assertFalse(source.contains("leftPants.xRot"));
    }
}
