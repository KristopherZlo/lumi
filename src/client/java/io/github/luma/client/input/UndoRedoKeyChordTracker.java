package io.github.luma.client.input;

import io.github.luma.ui.overlay.RecentChangesOverlayCoordinator;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Tracks the held Lumi overlay modifier and undo/redo keys as one-shot key chords.
 */
public final class UndoRedoKeyChordTracker {

    private final KeyBindingState keyBindingState;
    private boolean undoHeld;
    private boolean redoHeld;

    public UndoRedoKeyChordTracker() {
        this(new KeyBindingState());
    }

    UndoRedoKeyChordTracker(KeyBindingState keyBindingState) {
        this.keyBindingState = keyBindingState;
    }

    public TickResult tick(
            Minecraft client,
            boolean inputActive,
            boolean modifierHeld,
            KeyMapping undoKey,
            KeyMapping redoKey
    ) {
        boolean undoClicked = this.consumeClicks(undoKey);
        boolean redoClicked = this.consumeClicks(redoKey);
        return this.tick(
                inputActive,
                modifierHeld,
                this.keyBindingState.isDown(client, undoKey),
                this.keyBindingState.isDown(client, redoKey),
                undoClicked,
                redoClicked
        );
    }

    TickResult tick(
            boolean inputActive,
            boolean modifierHeld,
            boolean undoKeyDown,
            boolean redoKeyDown,
            boolean undoClicked,
            boolean redoClicked
    ) {
        if (!inputActive) {
            this.undoHeld = false;
            this.redoHeld = false;
            return TickResult.idle();
        }

        boolean currentUndoHeld = modifierHeld && undoKeyDown;
        boolean currentRedoHeld = modifierHeld && redoKeyDown;
        boolean undoRequested = modifierHeld && (undoClicked || (currentUndoHeld && !this.undoHeld));
        boolean redoRequested = modifierHeld && (redoClicked || (currentRedoHeld && !this.redoHeld));
        if (undoRequested && redoRequested) {
            redoRequested = false;
        }

        TickResult result = new TickResult(
                undoRequested,
                redoRequested,
                (currentRedoHeld || redoRequested) && !(currentUndoHeld || undoRequested)
                        ? RecentChangesOverlayCoordinator.PreviewTarget.REDO
                        : RecentChangesOverlayCoordinator.PreviewTarget.UNDO
        );

        this.undoHeld = currentUndoHeld;
        this.redoHeld = currentRedoHeld;
        return result;
    }

    private boolean consumeClicks(KeyMapping key) {
        if (key == null) {
            return false;
        }
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;
        }
        return clicked;
    }

    public record TickResult(
            boolean undoPressed,
            boolean redoPressed,
            RecentChangesOverlayCoordinator.PreviewTarget previewTarget
    ) {
        public static TickResult idle() {
            return new TickResult(false, false, RecentChangesOverlayCoordinator.PreviewTarget.UNDO);
        }
    }
}
