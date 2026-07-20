package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class OnboardingControllerTest {
    @Test
    void keepsTheNineStepCatalogAndBackNavigation() {
        OnboardingController controller = new OnboardingController();
        assertFalse(controller.canGoBack());
        controller.handle(navigate(OnboardingEvent.Direction.BACK));
        assertEquals("welcome", controller.current().id());
        assertEquals(List.of(
                "welcome", "break_block", "preview_changes", "save_shortcut",
                "open", "save_spotlight", "changes_spotlight",
                "commit_navigation", "finish"), OnboardingTour.pageIds());
    }

    @Test
    void targetEventsAdvanceAllNineSteps() {
        OnboardingController controller = new OnboardingController();
        assertEquals(OnboardingController.Effect.REFRESH,
                controller.handle(navigate(OnboardingEvent.Direction.NEXT)));
        assertEquals(OnboardingController.Effect.REOPEN, controller.handle(
                new OnboardingEvent.WorldCompleted(OnboardingTour.Kind.WORLD_EDIT)));
        assertEquals(OnboardingController.Effect.REOPEN, controller.handle(
                new OnboardingEvent.WorldCompleted(OnboardingTour.Kind.WORLD_PREVIEW)));
        assertEquals(OnboardingController.Effect.OPEN_SAVE, controller.handle(
                new OnboardingEvent.Shortcut(
                        OnboardingEvent.ShortcutKind.SAVE, true)));
        assertEquals(OnboardingController.Effect.REFRESH,
                controller.handle(new OnboardingEvent.SaveCompleted()));
        assertEquals(OnboardingController.Effect.OPEN_DASHBOARD, controller.handle(
                new OnboardingEvent.Shortcut(
                        OnboardingEvent.ShortcutKind.DASHBOARD, true)));
        for (OnboardingTour.Kind kind : List.of(
                OnboardingTour.Kind.SPOTLIGHT_SAVE,
                OnboardingTour.Kind.SPOTLIGHT_CHANGES,
                OnboardingTour.Kind.SPOTLIGHT_RESTORE)) {
            assertEquals(OnboardingController.Effect.REFRESH,
                    controller.handle(new OnboardingEvent.SpotlightActivated(kind)));
        }
        assertEquals(OnboardingController.Effect.OPEN_HOTKEYS, controller.handle(
                new OnboardingEvent.Shortcut(
                        OnboardingEvent.ShortcutKind.HOTKEYS, true)));
        assertTrue(controller.completed());
    }

    @Test
    void skipCompletesFromAnyStepAndReleaseDoesNotAdvance() {
        OnboardingController controller = new OnboardingController();
        controller.handle(navigate(OnboardingEvent.Direction.NEXT));
        assertEquals(OnboardingController.Effect.NONE, controller.handle(
                new OnboardingEvent.Shortcut(
                        OnboardingEvent.ShortcutKind.SAVE, false)));
        assertEquals(OnboardingController.Effect.COMPLETE,
                controller.handle(navigate(OnboardingEvent.Direction.SKIP)));
        assertTrue(controller.completed());
    }

    private static OnboardingEvent navigate(OnboardingEvent.Direction direction) {
        return new OnboardingEvent.Navigation(direction);
    }
}
