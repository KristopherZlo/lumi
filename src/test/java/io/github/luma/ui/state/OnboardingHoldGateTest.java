package io.github.luma.ui.state;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OnboardingHoldGateTest {

    @Test
    void fillsAfterRequiredHoldDuration() {
        OnboardingHoldGate gate = new OnboardingHoldGate(1500L);

        Assertions.assertFalse(gate.update(true, 500L));
        Assertions.assertEquals(1.0D / 3.0D, gate.progress(), 0.001D);

        Assertions.assertFalse(gate.update(true, 900L));
        Assertions.assertEquals(1400.0D / 1500.0D, gate.progress(), 0.001D);

        Assertions.assertTrue(gate.update(true, 100L));
        Assertions.assertEquals(1.0D, gate.progress(), 0.001D);
    }

    @Test
    void releasingShortcutResetsProgress() {
        OnboardingHoldGate gate = new OnboardingHoldGate(1500L);

        gate.update(true, 700L);
        gate.update(false, 100L);

        Assertions.assertEquals(0.0D, gate.progress(), 0.001D);
        Assertions.assertFalse(gate.update(true, 700L));
    }
}
