package io.github.luma.client.onboarding;

/**
 * Delays returning to the next onboarding card until the taught world action
 * has reached its terminal state and stayed visible for the confirmation span.
 */
final class OnboardingWorldPreviewDelay {

    private static final int DEFAULT_CONFIRMATION_TICKS = 60;

    private final int confirmationTicks;
    private boolean countdownStarted;
    private int remainingTicks;

    OnboardingWorldPreviewDelay() {
        this(DEFAULT_CONFIRMATION_TICKS);
    }

    OnboardingWorldPreviewDelay(int confirmationTicks) {
        this.confirmationTicks = Math.max(1, confirmationTicks);
    }

    void start() {
        this.countdownStarted = false;
        this.remainingTicks = 0;
    }

    boolean tick(boolean actionFinished) {
        if (!this.countdownStarted) {
            if (!actionFinished) {
                return false;
            }
            this.countdownStarted = true;
            this.remainingTicks = this.confirmationTicks;
            return false;
        }

        this.remainingTicks -= 1;
        return this.remainingTicks <= 0;
    }

    void clear() {
        this.countdownStarted = false;
        this.remainingTicks = 0;
    }
}
