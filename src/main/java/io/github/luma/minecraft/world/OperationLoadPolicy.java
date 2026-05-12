package io.github.luma.minecraft.world;

final class OperationLoadPolicy {

    private static final int WINDOW_SIZE = 32;
    private static final long RESPONSIVE_TICK_NANOS = 50_000_000L;
    private final double[] pressureSamples = new double[WINDOW_SIZE];
    private int sampleCount;
    private int nextSample;

    double nextAdaptiveScale(
            double currentScale,
            double minimumScale,
            double maximumScale,
            long elapsedNanos,
            long budgetNanos
    ) {
        if (elapsedNanos <= 0L || budgetNanos <= 0L) {
            return clamp(currentScale, minimumScale, maximumScale);
        }

        double pressure = Math.max(0.0D, (double) elapsedNanos / (double) pressureBudgetNanos(budgetNanos));
        this.record(pressure);
        double p95Pressure = this.percentile95();
        double next = currentScale;
        if (pressure >= 1.50D || p95Pressure >= 1.25D) {
            next *= 0.50D;
        } else if (pressure > 1.0D || p95Pressure > 1.0D) {
            next *= 0.70D;
        } else if (p95Pressure < 0.45D) {
            next *= 1.08D;
        } else if (p95Pressure < 0.65D) {
            next *= 1.03D;
        }
        return clamp(next, minimumScale, maximumScale);
    }

    static long pressureBudgetNanos(long budgetNanos) {
        if (budgetNanos <= 0L) {
            return 0L;
        }
        return Math.min(budgetNanos, RESPONSIVE_TICK_NANOS);
    }

    private void record(double pressure) {
        this.pressureSamples[this.nextSample] = pressure;
        this.nextSample = (this.nextSample + 1) % WINDOW_SIZE;
        this.sampleCount = Math.min(WINDOW_SIZE, this.sampleCount + 1);
    }

    private double percentile95() {
        if (this.sampleCount <= 0) {
            return 0.0D;
        }
        double[] sorted = new double[this.sampleCount];
        System.arraycopy(this.pressureSamples, 0, sorted, 0, this.sampleCount);
        java.util.Arrays.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(this.sampleCount * 0.95D) - 1);
        return sorted[index];
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
