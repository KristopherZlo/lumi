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
    void altLIsConsumedBeforeVanillaAdvancements() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiHotkeys.java"));

        assertTrue(source.contains("START_CLIENT_TICK"));
        assertTrue(source.contains("dashboard.same(client.options.keyAdvancements)"));
        assertTrue(source.contains("consume(client.options.keyAdvancements)"));
    }
}
