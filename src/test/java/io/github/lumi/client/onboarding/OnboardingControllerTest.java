package io.github.lumi.client.onboarding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OnboardingControllerTest {
    @Test
    void keepsTheTenStepCatalogAndBackNavigation() {
        OnboardingController controller = new OnboardingController();
        assertFalse(controller.canGoBack());
        controller.handle(navigate(OnboardingEvent.Direction.BACK));
        assertEquals("welcome", controller.current().id());
        assertEquals(List.of(
                "welcome", "break_block", "preview_changes", "undo_redo",
                "save_shortcut", "experiment", "open", "changes_spotlight",
                "commit_navigation", "finish"), OnboardingTour.pageIds());
    }

    @Test
    void targetEventsAdvanceTheHandsOnFlow() {
        OnboardingController controller = new OnboardingController();
        assertEquals(OnboardingController.Effect.ENTER_WORLD,
                controller.handle(navigate(OnboardingEvent.Direction.NEXT)));
        assertEquals(OnboardingController.Effect.NONE, controller.handle(
                new OnboardingEvent.WorldCompleted(OnboardingTour.Kind.WORLD_EDIT)));
        assertEquals(OnboardingController.Effect.NONE, controller.handle(
                new OnboardingEvent.WorldCompleted(OnboardingTour.Kind.WORLD_PREVIEW)));
        completeOperation(controller, OnboardingEvent.OperationKind.UNDO);
        assertEquals(OnboardingController.UndoRedoPhase.REDO,
                controller.undoRedoPhase());
        assertEquals(OnboardingController.Effect.REOPEN,
                completeOperation(controller, OnboardingEvent.OperationKind.REDO));
        assertEquals(OnboardingController.Effect.OPEN_SAVE, controller.handle(
                new OnboardingEvent.Shortcut(
                        OnboardingEvent.ShortcutKind.SAVE, true)));
        assertEquals(OnboardingController.Effect.ENTER_WORLD,
                completeOperation(controller, OnboardingEvent.OperationKind.SAVE));
        assertEquals(OnboardingController.Effect.REOPEN, controller.handle(
                new OnboardingEvent.WorldCompleted(
                        OnboardingTour.Kind.WORLD_EXPERIMENT)));
        assertEquals(OnboardingController.Effect.OPEN_DASHBOARD, controller.handle(
                new OnboardingEvent.Shortcut(
                        OnboardingEvent.ShortcutKind.DASHBOARD, true)));
        assertEquals(OnboardingController.Effect.REFRESH, controller.handle(
                new OnboardingEvent.SpotlightActivated(
                        OnboardingTour.Kind.SPOTLIGHT_COMPARE)));
        assertEquals(OnboardingController.Effect.REFRESH,
                completeOperation(controller, OnboardingEvent.OperationKind.RESTORE));
        assertEquals(OnboardingController.Effect.COMPLETE,
                controller.handle(navigate(OnboardingEvent.Direction.NEXT)));
        assertTrue(controller.completed());
    }

    @Test
    void skipCompletesFromAnyStepAndReleaseDoesNotAdvance() {
        OnboardingController controller = new OnboardingController();
        controller.handle(navigate(OnboardingEvent.Direction.NEXT));
        assertEquals(OnboardingController.Effect.NONE, controller.handle(
                new OnboardingEvent.Shortcut(
                        OnboardingEvent.ShortcutKind.DASHBOARD, false)));
        assertEquals(OnboardingController.Effect.COMPLETE,
                controller.handle(navigate(OnboardingEvent.Direction.SKIP)));
        assertTrue(controller.completed());
    }

    private static OnboardingEvent navigate(OnboardingEvent.Direction direction) {
        return new OnboardingEvent.Navigation(direction);
    }

    private static OnboardingController.Effect completeOperation(
            OnboardingController controller,
            OnboardingEvent.OperationKind operation) {
        UUID requestId = UUID.randomUUID();
        assertEquals(OnboardingController.Effect.NONE, controller.handle(
                new OnboardingEvent.OperationStarted(operation, requestId)));
        return controller.handle(
                new OnboardingEvent.OperationCompleted(requestId, true));
    }
}
