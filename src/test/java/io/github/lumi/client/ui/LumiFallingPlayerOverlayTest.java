package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiFallingPlayerOverlayTest {
    @Test
    void matchesOnlyThePlayersNameAndFallsPastTheScreen() {
        assertTrue(LumiFallingPlayerOverlay.matchesPlayerName(
                "  Builder  ", "builder"));
        assertFalse(LumiFallingPlayerOverlay.matchesPlayerName(
                "Builder save", "Builder"));
        assertTrue(LumiFallingPlayerOverlay.fallY(0.0F, 240, 74) < 0);
        assertTrue(LumiFallingPlayerOverlay.fallY(1.0F, 240, 74) > 240);
    }
}
