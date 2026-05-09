package io.github.luma.client.onboarding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingWorldPreviewDelayTest {

    @Test
    void waitsForActionFinalBeforeStartingConfirmationCountdown() {
        OnboardingWorldPreviewDelay delay = new OnboardingWorldPreviewDelay(3);

        delay.start();

        assertFalse(delay.tick(false));
        assertFalse(delay.tick(false));
        assertFalse(delay.tick(true));
        assertFalse(delay.tick(true));
        assertFalse(delay.tick(true));
        assertTrue(delay.tick(true));
    }

    @Test
    void clearStopsPendingCountdown() {
        OnboardingWorldPreviewDelay delay = new OnboardingWorldPreviewDelay(2);

        delay.start();
        assertFalse(delay.tick(true));
        delay.clear();

        assertFalse(delay.tick(false));
        assertFalse(delay.tick(true));
        assertFalse(delay.tick(true));
        assertTrue(delay.tick(true));
    }
}
