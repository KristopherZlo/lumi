package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(source.contains("AvatarRenderState"));
        assertTrue(source.contains("state.skin = skin"));
        assertTrue(source.contains("state.showCape = skin.cape() != null"));
        assertTrue(source.contains("state.yRot = 0.0F"));
        assertTrue(source.contains("CAPE_CYCLE_MILLIS = 1_700L"));
        assertTrue(source.contains("Math.sin"));
        assertTrue(source.contains("ORBIT_CYCLE_MILLIS"));
        assertTrue(source.contains("graphics.submitEntityRenderState("));
        assertFalse(source.contains("graphics.submitSkinRenderState("));
        assertFalse(source.contains(
                "import net.minecraft.client.model.player.PlayerCapeModel"));
        assertTrue(source.contains("LumiUiScale.current().renderScale("));
        assertTrue(source.contains("scale * uiScale"));
        assertTrue(source.contains("scaled(x + width, uiScale)"));
        assertFalse(source.contains("owo"));
        assertTrue(source.contains("public boolean mouseScrolled("));
    }

    @Test
    void capeUsesReversedSlowerWave() {
        float resting = LumiSpecialThanksScreen.capeAngleDegrees(0);
        float trailing = LumiSpecialThanksScreen.capeAngleDegrees(425);

        assertTrue(resting > 0.0F);
        assertTrue(trailing > resting);
        assertEquals(15.0F, resting);
        assertEquals(21.0F, trailing);
        assertEquals(9.0F, LumiSpecialThanksScreen.capeFlapDegrees(0));
        assertEquals(15.0F, LumiSpecialThanksScreen.capeFlapDegrees(425));
    }

    @Test
    void cardsFitAndBecomeScrollableAsTheCatalogGrows() {
        assertCardGeometry(156, 2, 1);
        assertCardGeometry(176, 2, 2);
        assertCardGeometry(216, 2, 2);
        assertCardGeometry(320, 5, 4);
    }

    private static void assertCardGeometry(
            int panelHeight, int entries, int expectedRows) {
        int rows = LumiSpecialThanksScreen.visibleCardRows(panelHeight, entries);
        int height = LumiSpecialThanksScreen.cardHeight(panelHeight, rows);
        assertEquals(expectedRows, rows);
        assertTrue(58 + rows * height + Math.max(0, rows - 1) * 8
                <= panelHeight - 12);
    }
}
