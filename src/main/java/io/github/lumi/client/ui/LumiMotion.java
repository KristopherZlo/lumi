package io.github.lumi.client.ui;

import java.util.Objects;
import java.util.function.LongSupplier;

/** One real-time UI motion with a fast start and smooth stop. */
final class LumiMotion {
    private static boolean enabled = true;
    private final LongSupplier nanoTime;
    private long startedNanos;
    private long durationNanos;
    private float fromValue;
    private float targetValue;

    LumiMotion() {
        this(System::nanoTime);
    }

    LumiMotion(float initialValue) {
        this();
        fromValue = targetValue = initialValue;
    }

    LumiMotion(LongSupplier nanoTime) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    void start(int durationMillis) {
        if (durationMillis <= 0) {
            throw new IllegalArgumentException("durationMillis must be positive");
        }
        startedNanos = nanoTime.getAsLong();
        durationNanos = durationMillis * 1_000_000L;
    }

    float value() {
        if (!enabled || durationNanos == 0L) {
            return 1.0F;
        }
        long elapsed = Math.max(0L, nanoTime.getAsLong() - startedNanos);
        float progress = Math.min(1.0F, (float) elapsed / durationNanos);
        return easeOutQuint(progress);
    }

    float toward(float target) {
        if (!enabled) {
            fromValue = targetValue = target;
            durationNanos = 0;
            return target;
        }
        float current = fromValue + (targetValue - fromValue) * value();
        if (target != targetValue) {
            fromValue = current;
            targetValue = target;
            start(300);
        }
        return current;
    }

    static void setEnabled(boolean value) {
        enabled = value;
    }

    boolean running() {
        return durationNanos != 0L && value() < 1.0F;
    }

    static float easeOutQuint(float progress) {
        float clamped = Math.max(0.0F, Math.min(1.0F, progress));
        float remaining = 1.0F - clamped;
        return 1.0F - remaining * remaining * remaining * remaining * remaining;
    }
}
