package io.github.luma.ui.state;

public final class OnboardingHoldGate {

    public static final long DEFAULT_REQUIRED_HOLD_MILLIS = 1500L;

    private final long requiredHoldMillis;
    private long heldMillis;
    private boolean completed;

    public OnboardingHoldGate() {
        this(DEFAULT_REQUIRED_HOLD_MILLIS);
    }

    public OnboardingHoldGate(long requiredHoldMillis) {
        this.requiredHoldMillis = Math.max(1L, requiredHoldMillis);
    }

    public boolean update(boolean held, long elapsedMillis) {
        if (this.completed) {
            return true;
        }
        if (!held) {
            this.heldMillis = 0L;
            return false;
        }
        this.heldMillis = Math.min(this.requiredHoldMillis, this.heldMillis + Math.max(0L, elapsedMillis));
        this.completed = this.heldMillis >= this.requiredHoldMillis;
        return this.completed;
    }

    public double progress() {
        double rawProgress = (double) this.heldMillis / (double) this.requiredHoldMillis;
        return Math.max(0.0D, Math.min(1.0D, rawProgress));
    }

    public void reset() {
        this.heldMillis = 0L;
        this.completed = false;
    }
}
