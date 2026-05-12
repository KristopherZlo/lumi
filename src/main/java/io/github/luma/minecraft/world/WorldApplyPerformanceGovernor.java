package io.github.luma.minecraft.world;

/**
 * Owns runtime apply throttling for a single world operation.
 */
final class WorldApplyPerformanceGovernor {

    private static final long MIN_REMAINING_NANOS = 1_000_000L;
    private static final double START_GUARD_FRACTION = 0.10D;

    private final OperationLoadPolicy loadPolicy = new OperationLoadPolicy();
    private final ApplyCostModel costModel = new ApplyCostModel();
    private double adaptiveScale = 1.0D;

    WorldApplyBudget planBudget(
            WorldApplyBudgetPlanner planner,
            double progressFraction,
            WorldApplyProfile profile,
            double minimumScale,
            double maximumScale
    ) {
        this.adaptiveScale = clamp(this.adaptiveScale, minimumScale, maximumScale);
        return planner.plan(progressFraction, this.adaptiveScale, profile);
    }

    void recordTick(long elapsedNanos, long budgetNanos, double minimumScale, double maximumScale) {
        this.adaptiveScale = this.loadPolicy.nextAdaptiveScale(
                this.adaptiveScale,
                minimumScale,
                maximumScale,
                elapsedNanos,
                budgetNanos
        );
    }

    ChunkStartDecision evaluateChunkStart(
            ChunkBatch batch,
            WorldApplyBudget budget,
            long elapsedThisTickNanos,
            int processedWorkThisTick
    ) {
        if (batch == null || budget == null || processedWorkThisTick <= 0) {
            return ChunkStartDecision.allow(0L, 0.0D);
        }
        long pressureBudgetNanos = OperationLoadPolicy.pressureBudgetNanos(budget.maxNanos());
        double tickPressure = pressureBudgetNanos <= 0L
                ? 0.0D
                : (double) Math.max(0L, elapsedThisTickNanos) / pressureBudgetNanos;
        if (!this.costModel.hasSamples()) {
            return elapsedThisTickNanos >= pressureBudgetNanos
                    ? ChunkStartDecision.defer("responsive-window-consumed", 0L, tickPressure)
                    : ChunkStartDecision.allow(0L, tickPressure);
        }

        long remainingNanos = pressureBudgetNanos - Math.max(0L, elapsedThisTickNanos);
        long predictedNanos = this.costModel.estimateChunkNanos(batch);
        long guardNanos = Math.max(MIN_REMAINING_NANOS, Math.round(pressureBudgetNanos * START_GUARD_FRACTION));
        if (remainingNanos <= MIN_REMAINING_NANOS) {
            return ChunkStartDecision.defer("no-responsive-budget", predictedNanos, tickPressure);
        }
        if (predictedNanos > 0L && predictedNanos + guardNanos > remainingNanos) {
            return ChunkStartDecision.defer("predicted-chunk-overrun", predictedNanos, tickPressure);
        }
        return ChunkStartDecision.allow(predictedNanos, tickPressure);
    }

    void recordWork(ApplyWorkKind kind, int units, long elapsedNanos) {
        this.costModel.record(kind, units, elapsedNanos);
    }

    double adaptiveScale() {
        return this.adaptiveScale;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record ChunkStartDecision(
            boolean allowed,
            String reason,
            long predictedNanos,
            double tickPressure
    ) {

        private static ChunkStartDecision allow(long predictedNanos, double tickPressure) {
            return new ChunkStartDecision(true, "allow", predictedNanos, tickPressure);
        }

        private static ChunkStartDecision defer(String reason, long predictedNanos, double tickPressure) {
            return new ChunkStartDecision(false, reason, predictedNanos, tickPressure);
        }
    }
}
