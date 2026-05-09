package io.github.luma.client.onboarding;

import io.github.luma.client.input.UndoRedoKeyController;
import io.github.luma.ui.onboarding.OnboardingTour;
import java.util.Objects;
import net.minecraft.client.Minecraft;

/**
 * Turns onboarding hold confirmations into the same queued undo/redo path used
 * by real Lumi shortcut chords while normal shortcuts are suppressed.
 */
final class OnboardingWorldPreviewShortcutController {

    private final SyntheticUndoRedoInput input;
    private OnboardingTour.Transition transition = OnboardingTour.Transition.NONE;
    private boolean queued;

    OnboardingWorldPreviewShortcutController() {
        this(new UndoRedoSyntheticInput(new UndoRedoKeyController()));
    }

    OnboardingWorldPreviewShortcutController(SyntheticUndoRedoInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    void start(OnboardingTour.Transition transition) {
        this.transition = transition == null ? OnboardingTour.Transition.NONE : transition;
        this.queued = false;
    }

    void tick(Minecraft client) {
        if (!this.undoRedoTransition()) {
            return;
        }
        if (!this.queued) {
            this.queue(client);
            this.queued = true;
        }
        this.input.tick(client);
    }

    void clear() {
        this.transition = OnboardingTour.Transition.NONE;
        this.queued = false;
    }

    private void queue(Minecraft client) {
        if (this.transition == OnboardingTour.Transition.EXECUTE_REDO) {
            this.input.redo(client);
        } else {
            this.input.undo(client);
        }
    }

    private boolean undoRedoTransition() {
        return this.transition == OnboardingTour.Transition.EXECUTE_UNDO
                || this.transition == OnboardingTour.Transition.EXECUTE_REDO;
    }

    interface SyntheticUndoRedoInput {

        void undo(Minecraft client);

        void redo(Minecraft client);

        void tick(Minecraft client);
    }

    private static final class UndoRedoSyntheticInput implements SyntheticUndoRedoInput {

        private final UndoRedoKeyController controller;

        private UndoRedoSyntheticInput(UndoRedoKeyController controller) {
            this.controller = Objects.requireNonNull(controller, "controller");
        }

        @Override
        public void undo(Minecraft client) {
            this.controller.undo(client);
        }

        @Override
        public void redo(Minecraft client) {
            this.controller.redo(client);
        }

        @Override
        public void tick(Minecraft client) {
            this.controller.tick(client);
        }
    }
}
