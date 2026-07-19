package io.github.lumi.client.onboarding;

/** Requires a continuous shortcut hold before onboarding advances. */
public final class OnboardingHoldGate {
    public static final long REQUIRED_MILLIS = 800L;
    private final long requiredMillis;
    private long heldMillis;

    public OnboardingHoldGate() {
        this(REQUIRED_MILLIS);
    }

    OnboardingHoldGate(long requiredMillis) {
        this.requiredMillis = Math.max(1L, requiredMillis);
    }

    public boolean update(boolean held, long elapsedMillis) {
        if (!held) {
            heldMillis = 0L;
            return false;
        }
        heldMillis = Math.min(
                requiredMillis, heldMillis + Math.max(0L, elapsedMillis));
        return heldMillis >= requiredMillis;
    }

    public double progress() {
        return Math.max(0.0D, Math.min(
                1.0D, (double) heldMillis / (double) requiredMillis));
    }

    public void reset() {
        heldMillis = 0L;
    }
}
