package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiHotkeysTest {
    @Test
    void dashboardUsesAltLOnlyDuringNormalPlay() {
        assertEquals(InputConstants.KEY_L, LumiHotkeys.defaultDashboardKey());
        assertFalse(LumiHotkeys.canOpenDashboard(true, false));
        assertTrue(LumiHotkeys.canOpenDashboard(true, true));
        assertFalse(LumiHotkeys.canOpenDashboard(false, true));
    }

    @Test
    void compareHighlightKeepsTheLegacyHBinding() {
        assertEquals(InputConstants.KEY_H, LumiHotkeys.defaultCompareOverlayKey());
    }

    @Test
    void quickRollbackDoesNotRequireTheAltChord() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));
        assertTrue(source.contains(
                "consume(rollback, normalPlay, HotkeyActionDispatcher.Action.QUICK_ROLLBACK)"));
        assertFalse(source.contains(
                "consume(rollback, canUseChord, HotkeyActionDispatcher.Action.QUICK_ROLLBACK)"));
    }

    @Test
    void actionModifierIsARealRemappableBinding() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));
        assertTrue(source.contains("\"key.lumi.action_modifier\""));
        assertTrue(source.contains("actionModifier.isDown()"));
        assertTrue(source.contains(
                "KeyBindingHelper.registerKeyBinding(actionModifier)"));
    }

    @Test
    void altLIsConsumedBeforeVanillaAdvancements() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));

        assertTrue(source.contains("START_CLIENT_TICK"));
        assertTrue(source.contains("dashboard.same(client.options.keyAdvancements)"));
        assertTrue(source.contains("consume(client.options.keyAdvancements)"));
    }
}
