package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OnboardingHoldGateTest {
    @Test
    void requiresOneContinuousHold() {
        OnboardingHoldGate gate = new OnboardingHoldGate(800);
        assertFalse(gate.update(true, 500));
        assertEquals(0.625, gate.progress());
        assertFalse(gate.update(false, 1));
        assertFalse(gate.update(true, 799));
        assertTrue(gate.update(true, 1));
        gate.reset();
        assertEquals(0.0, gate.progress());
    }
}
