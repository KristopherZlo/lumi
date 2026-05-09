package io.github.luma.client.onboarding;

import io.github.luma.domain.model.OperationHandle;
import io.github.luma.ui.onboarding.OnboardingTour;
import java.time.Instant;
import net.minecraft.client.Minecraft;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingWorldPreviewShortcutControllerTest {

    @Test
    void undoPreviewQueuesUndoOnceAndWaitsForOperationTerminal() {
        RecordingInput input = new RecordingInput();
        OnboardingWorldPreviewShortcutController controller = new OnboardingWorldPreviewShortcutController(input);

        controller.start(OnboardingTour.Transition.EXECUTE_UNDO);
        assertFalse(controller.tick(null));
        assertFalse(controller.tick(null));
        input.terminal = true;
        assertTrue(controller.tick(null));

        assertEquals(1, input.undoRequests);
        assertEquals(0, input.redoRequests);
        assertEquals(3, input.ticks);
    }

    @Test
    void redoPreviewQueuesRedoOnceAndWaitsForOperationTerminal() {
        RecordingInput input = new RecordingInput();
        OnboardingWorldPreviewShortcutController controller = new OnboardingWorldPreviewShortcutController(input);

        controller.start(OnboardingTour.Transition.EXECUTE_REDO);
        assertFalse(controller.tick(null));
        input.terminal = true;
        assertTrue(controller.tick(null));

        assertEquals(0, input.undoRequests);
        assertEquals(1, input.redoRequests);
        assertEquals(2, input.ticks);
    }

    @Test
    void noOperationTerminalCompletesPreviewImmediately() {
        RecordingInput input = new RecordingInput();
        input.terminalWithoutOperation = true;
        OnboardingWorldPreviewShortcutController controller = new OnboardingWorldPreviewShortcutController(input);

        controller.start(OnboardingTour.Transition.EXECUTE_UNDO);

        assertTrue(controller.tick(null));
        assertEquals(1, input.undoRequests);
        assertEquals(1, input.ticks);
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
        private boolean terminal;
        private boolean terminalWithoutOperation;
        private OperationHandle handle = new OperationHandle(
                "op",
                "project",
                "undo-action",
                Instant.parse("2026-05-09T00:00:00Z"),
                false
        );

        @Override
        public void undo(Minecraft client) {
            this.undoRequests += 1;
        }

        @Override
        public void redo(Minecraft client) {
            this.redoRequests += 1;
        }

        @Override
        public OnboardingWorldPreviewShortcutController.SyntheticTickResult tick(Minecraft client) {
            this.ticks += 1;
            return this.terminalWithoutOperation
                    ? OnboardingWorldPreviewShortcutController.SyntheticTickResult.terminalNoOperation()
                    : new OnboardingWorldPreviewShortcutController.SyntheticTickResult(this.handle, false);
        }

        @Override
        public boolean operationTerminal(Minecraft client, OperationHandle handle) {
            return this.terminal && this.handle.equals(handle);
        }
    }
}
