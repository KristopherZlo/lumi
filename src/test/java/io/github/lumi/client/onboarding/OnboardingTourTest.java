package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OnboardingTourTest {
    @Test
    void staysWithinTheRetainedTour() {
        OnboardingTour tour = new OnboardingTour();

        assertEquals("welcome", tour.current().id());
        tour.previous();
        assertEquals("welcome", tour.current().id());
        for (int index = 1; index < OnboardingTour.pageCount(); index++) {
            tour.next();
        }
        assertEquals("finish", tour.current().id());
        tour.next();
        assertEquals("finish", tour.current().id());
        tour.previous();
        assertEquals("commit_navigation", tour.current().id());
        assertEquals(List.of(
                "welcome", "break_block", "preview_changes", "save_shortcut",
                "open", "save_spotlight", "changes_spotlight",
                "commit_navigation", "finish"), OnboardingTour.pageIds());
    }

    @Test
    void everyPageAfterWelcomeCanGoBack() {
        OnboardingTour tour = new OnboardingTour();
        assertFalse(tour.canGoBack());
        for (int index = 1; index < OnboardingTour.pageCount(); index++) {
            tour.next();
            assertTrue(tour.canGoBack());
        }
    }

    @Test
    void advancesOnlyFromTheMatchingWorldStep() {
        OnboardingTour tour = new OnboardingTour();
        assertFalse(tour.advanceWorldEdit());
        tour.next();
        assertTrue(tour.advanceWorldEdit());
        assertTrue(tour.advancePendingPreview());
        assertEquals("save_shortcut", tour.current().id());
    }
}
