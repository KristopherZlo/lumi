package io.github.luma.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LumiShortcutInteractionGateTest {

    @Test
    void activeShortcutSuppressesCurrentAndFollowingTicks() {
        LumiShortcutInteractionGate gate = new LumiShortcutInteractionGate();

        gate.tick(true, true);
        assertTrue(gate.suppressed());

        gate.tick(true, false);
        assertTrue(gate.suppressed());

        gate.tick(true, false);
        assertFalse(gate.suppressed());
    }

    @Test
    void inactiveInputClearsSuppression() {
        LumiShortcutInteractionGate gate = new LumiShortcutInteractionGate();

        gate.tick(true, true);
        gate.tick(false, true);

        assertFalse(gate.suppressed());
    }

    @Test
    void missingWorldContextDoesNotSuppressInteraction() {
        LumiShortcutInteractionGate gate = new LumiShortcutInteractionGate();

        gate.tick(true, true);

        assertFalse(gate.shouldSuppressWorldInteraction(null));
    }
}
