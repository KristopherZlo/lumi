package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals("safe_restore", tour.current().id());
    }
}
