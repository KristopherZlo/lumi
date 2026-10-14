package io.github.lumi.domain.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class HudDisplayModeTest {
    @Test
    void preservesLegacyBooleanIdsAndRejectsUnknownValues() {
        assertEquals(1, HudDisplayMode.GUI.id());
        assertEquals(0, HudDisplayMode.BOSSBAR.id());
        assertEquals(2, HudDisplayMode.NONE.id());
        assertEquals(HudDisplayMode.GUI, HudDisplayMode.fromId(1));
        assertEquals(HudDisplayMode.BOSSBAR, HudDisplayMode.fromId(0));
        assertEquals(HudDisplayMode.NONE, HudDisplayMode.fromId(2));
        assertThrows(IllegalArgumentException.class,
                () -> HudDisplayMode.fromId(3));
    }
}
