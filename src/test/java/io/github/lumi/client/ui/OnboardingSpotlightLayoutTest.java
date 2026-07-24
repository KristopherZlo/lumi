package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.client.onboarding.OnboardingTour;
import org.junit.jupiter.api.Test;

class OnboardingSpotlightLayoutTest {
    private final OnboardingSpotlightLayout layout =
            new OnboardingSpotlightLayout();

    @Test
    void targetsTheDashboardActions() {
        var changes = layout.place(
                OnboardingTour.Kind.SPOTLIGHT_COMPARE, 1280, 720).hole();
        var restore = layout.place(
                OnboardingTour.Kind.SPOTLIGHT_RESTORE, 1280, 720).hole();

        assertEquals(36, changes.width());
        assertEquals(36, restore.width());
        assertTrue(restore.y() > changes.y());
    }

    @Test
    void promptStaysInsideSmallScreens() {
        var prompt = layout.place(
                OnboardingTour.Kind.SPOTLIGHT_COMPARE, 360, 240).prompt();

        assertTrue(prompt.x() >= 0);
        assertTrue(prompt.y() >= 0);
        assertTrue(prompt.right() <= 360);
        assertTrue(prompt.bottom() <= 240);
    }
}
