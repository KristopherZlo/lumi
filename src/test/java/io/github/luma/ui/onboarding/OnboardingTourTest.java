package io.github.luma.ui.onboarding;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class OnboardingTourTest {

    @Test
    void firstTourUsesSafeLoopPageOrder() {
        Assertions.assertEquals(List.of(
                "welcome",
                "open",
                "save_spotlight",
                "changes_spotlight",
                "fix_mistakes",
                "finish"
        ), OnboardingTour.pageIds());
    }

    @Test
    void firstTourHasSixPages() {
        Assertions.assertEquals(6, OnboardingTour.pageCount());
    }
}
