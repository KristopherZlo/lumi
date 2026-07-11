package io.github.luma.client.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/** Tracks one-shot action-key chords used by selection editing. */
public final class LumiActionKeyChordTracker {

    private final KeyBindingState keyBindingState;
    private boolean undoHeld;
    private boolean redoHeld;

    public LumiActionKeyChordTracker() {
        this(new KeyBindingState());
    }

    LumiActionKeyChordTracker(KeyBindingState keyBindingState) {
        this.keyBindingState = keyBindingState;
    }

    public TickResult tick(
            Minecraft client,
            boolean inputActive,
            boolean modifierHeld,
            KeyMapping undoKey,
            KeyMapping redoKey
    ) {
        return this.tick(
                inputActive,
                modifierHeld,
                this.keyBindingState.isDown(client, undoKey),
                this.keyBindingState.isDown(client, redoKey),
                consumeClicks(undoKey),
                consumeClicks(redoKey)
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
        this.undoHeld = currentUndoHeld;
        this.redoHeld = currentRedoHeld;
        return new TickResult(
                undoRequested,
                redoRequested,
                currentUndoHeld || currentRedoHeld || undoRequested || redoRequested
        );
    }

    private static boolean consumeClicks(KeyMapping key) {
        if (key == null) {
            return false;
        }
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;
        }
        return clicked;
    }

    public record TickResult(boolean undoPressed, boolean redoPressed, boolean chordActive) {
        public static TickResult idle() {
            return new TickResult(false, false, false);
        }
    }
}
