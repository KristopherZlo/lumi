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
                "break_block",
                "undo_world",
                "redo_world",
                "shortcuts",
                "finish"
        ), OnboardingTour.pageIds());
    }

    @Test
    void firstTourHasNinePages() {
        Assertions.assertEquals(9, OnboardingTour.pageCount());
    }
}
