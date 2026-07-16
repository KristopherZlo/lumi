package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

class LumiHotkeysTest {
    @Test
    void dashboardUsesLegacyUWithoutRequiringAlt() {
        assertEquals(InputConstants.KEY_U, LumiHotkeys.defaultDashboardKey());
        assertTrue(LumiHotkeys.canOpenDashboard(true, false));
        assertTrue(LumiHotkeys.canOpenDashboard(true, true));
        assertFalse(LumiHotkeys.canOpenDashboard(false, true));
    }
}
