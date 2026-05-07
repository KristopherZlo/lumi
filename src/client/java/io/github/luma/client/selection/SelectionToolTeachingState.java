package io.github.luma.client.selection;

/**
 * Small gate for the one-time wooden-sword selection teaching message.
 */
public final class SelectionToolTeachingState {

    public static final int DEFAULT_DISPLAY_TICKS = 100;

    private final int displayTicks;
    private int remainingDisplayTicks;
    private boolean completedThisSession;
    private boolean lastHintAllowed;

    public SelectionToolTeachingState() {
        this(DEFAULT_DISPLAY_TICKS);
    }

    SelectionToolTeachingState(int displayTicks) {
        this.displayTicks = Math.max(1, displayTicks);
    }

    public void observeHintAllowed(boolean hintAllowed) {
        if (hintAllowed && !this.lastHintAllowed) {
            this.completedThisSession = false;
        }
        this.lastHintAllowed = hintAllowed;
    }

    public boolean shouldStart(boolean inputActive, boolean toolHeld, boolean hintAllowed) {
        return inputActive
                && toolHeld
                && hintAllowed
                && !this.active()
                && !this.completedThisSession;
    }

    public void start() {
        this.remainingDisplayTicks = this.displayTicks;
    }

    public boolean active() {
        return this.remainingDisplayTicks > 0;
    }

    public boolean tickDisplay() {
        if (!this.active()) {
            return false;
        }
        this.remainingDisplayTicks--;
        if (this.remainingDisplayTicks == 0) {
            this.completedThisSession = true;
            return true;
        }
        return false;
    }
}
