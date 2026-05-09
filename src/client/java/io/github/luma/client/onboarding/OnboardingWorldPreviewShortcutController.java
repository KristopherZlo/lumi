package io.github.luma.client.onboarding;

import io.github.luma.client.input.UndoRedoKeyController;
import io.github.luma.domain.model.OperationHandle;
import io.github.luma.domain.model.OperationSnapshot;
import io.github.luma.minecraft.world.WorldOperationManager;
import io.github.luma.ui.controller.ClientProjectAccess;
import io.github.luma.ui.onboarding.OnboardingTour;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * Turns onboarding hold confirmations into the same queued undo/redo path used
 * by real Lumi shortcut chords while normal shortcuts are suppressed.
 */
final class OnboardingWorldPreviewShortcutController {

    private final SyntheticUndoRedoInput input;
    private OnboardingTour.Transition transition = OnboardingTour.Transition.NONE;
    private OperationHandle operationHandle;
    private boolean queued;
    private boolean actionFinished;

    OnboardingWorldPreviewShortcutController() {
        this(new UndoRedoSyntheticInput(new UndoRedoKeyController()));
    }

    OnboardingWorldPreviewShortcutController(SyntheticUndoRedoInput input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    void start(OnboardingTour.Transition transition) {
        this.transition = transition == null ? OnboardingTour.Transition.NONE : transition;
        this.operationHandle = null;
        this.queued = false;
        this.actionFinished = false;
    }

    boolean tick(Minecraft client) {
        if (!this.undoRedoTransition()) {
            return false;
        }
        if (this.actionFinished) {
            return true;
        }
        if (!this.queued) {
            this.queue(client);
            this.queued = true;
        }
        SyntheticTickResult result = this.input.tick(client);
        if (result.terminalWithoutOperation()) {
            this.actionFinished = true;
            return true;
        }
        if (result.operationHandle() != null) {
            this.operationHandle = result.operationHandle();
        }
        if (this.operationHandle != null && this.input.operationTerminal(client, this.operationHandle)) {
            this.actionFinished = true;
        }
        return this.actionFinished;
    }

    void clear() {
        this.transition = OnboardingTour.Transition.NONE;
        this.operationHandle = null;
        this.queued = false;
        this.actionFinished = false;
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

        SyntheticTickResult tick(Minecraft client);

        boolean operationTerminal(Minecraft client, OperationHandle handle);
    }

    record SyntheticTickResult(OperationHandle operationHandle, boolean terminalWithoutOperation) {

        static SyntheticTickResult idle() {
            return new SyntheticTickResult(null, false);
        }

        static SyntheticTickResult terminalNoOperation() {
            return new SyntheticTickResult(null, true);
        }
    }

    private static final class UndoRedoSyntheticInput implements SyntheticUndoRedoInput {

        private final UndoRedoKeyController controller;
        private final WorldOperationManager worldOperationManager = WorldOperationManager.getInstance();

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
        public SyntheticTickResult tick(Minecraft client) {
            UndoRedoKeyController.TickResult result = this.controller.tick(client);
            if (result.terminalWithoutOperation()) {
                return SyntheticTickResult.terminalNoOperation();
            }
            return result.operationHandle() == null
                    ? SyntheticTickResult.idle()
                    : new SyntheticTickResult(result.operationHandle(), false);
        }

        @Override
        public boolean operationTerminal(Minecraft client, OperationHandle handle) {
            if (client == null || handle == null || !client.hasSingleplayerServer()) {
                return false;
            }
            MinecraftServer server = ClientProjectAccess.requireSingleplayerServer(client);
            return this.worldOperationManager.snapshot(server, handle)
                    .map(OperationSnapshot::terminal)
                    .orElse(false);
        }
    }
}
