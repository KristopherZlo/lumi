package io.github.luma.client.onboarding;

import io.github.luma.ui.onboarding.OnboardingTour;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OnboardingWorldPreviewShortcutControllerTest {

    @Test
    void undoPreviewQueuesUndoOnceAndTicksEveryFrame() {
        RecordingInput input = new RecordingInput();
        OnboardingWorldPreviewShortcutController controller = new OnboardingWorldPreviewShortcutController(input);

        controller.start(OnboardingTour.Transition.EXECUTE_UNDO);
        controller.tick(null);
        controller.tick(null);

        assertEquals(1, input.undoRequests);
        assertEquals(0, input.redoRequests);
        assertEquals(2, input.ticks);
    }

    @Test
    void redoPreviewQueuesRedoOnceAndTicksEveryFrame() {
        RecordingInput input = new RecordingInput();
        OnboardingWorldPreviewShortcutController controller = new OnboardingWorldPreviewShortcutController(input);

        controller.start(OnboardingTour.Transition.EXECUTE_REDO);
        controller.tick(null);
        controller.tick(null);

        assertEquals(0, input.undoRequests);
        assertEquals(1, input.redoRequests);
        assertEquals(2, input.ticks);
    }

    @Test
    void clearStopsPreviewShortcutTicks() {
        RecordingInput input = new RecordingInput();
        OnboardingWorldPreviewShortcutController controller = new OnboardingWorldPreviewShortcutController(input);

        controller.start(OnboardingTour.Transition.EXECUTE_UNDO);
        controller.clear();
        controller.tick(null);

        assertEquals(0, input.undoRequests);
        assertEquals(0, input.redoRequests);
        assertEquals(0, input.ticks);
    }

    private static final class RecordingInput implements OnboardingWorldPreviewShortcutController.SyntheticUndoRedoInput {

        private int undoRequests;
        private int redoRequests;
        private int ticks;

        @Override
        public void undo(Minecraft client) {
            this.undoRequests += 1;
        }

        @Override
        public void redo(Minecraft client) {
            this.redoRequests += 1;
        }

        @Override
        public void tick(Minecraft client) {
            this.ticks += 1;
        }
    }
}
