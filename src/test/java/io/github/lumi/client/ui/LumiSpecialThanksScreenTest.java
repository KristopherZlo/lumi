package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiSpecialThanksScreenTest {
    @Test
    void rendersBundledCreditsAsAnimatedNativePlayerModels() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiSpecialThanksScreen.java"));

        assertTrue(source.contains("new SpecialThanksCatalogSource().loadBundled()"));
        assertTrue(source.contains("MinecraftSpecialThanksSkinResolver"));
        assertTrue(source.contains("ModelLayers.PLAYER"));
        assertTrue(source.contains("ModelLayers.PLAYER_SLIM"));
        assertTrue(source.contains("PlayerModelType.SLIM"));
        assertTrue(source.contains("model.resetPose()"));
        assertTrue(source.contains("model.setAllVisible(true)"));
        assertTrue(source.contains("Math.sin"));
        assertTrue(source.contains("ORBIT_CYCLE_MILLIS"));
        assertTrue(source.contains("graphics.submitSkinRenderState("));
        assertTrue(source.contains("LumiUiScale.current().renderScale("));
        assertTrue(source.contains("scale * uiScale"));
        assertTrue(source.contains("scaled(x + width, uiScale)"));
        assertFalse(source.contains("owo"));
        assertFalse(source.contains("Cape"));
    }
}
