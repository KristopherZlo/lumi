package io.github.luma.minecraft.world;

import java.util.Arrays;

/**
 * Tracks observed chunk apply cost so fast operations avoid starting a chunk
 * when the current tick no longer has enough budget for its expected shape.
 */
final class WorldApplyChunkCostTracker {

    private static final int WINDOW_SIZE = 16;
    private static final long MIN_REMAINING_NANOS = 1_000_000L;
    private static final double START_GUARD_FRACTION = 0.10D;

    private final double[] nanosPerWeightSamples = new double[WINDOW_SIZE];
    private int sampleCount;
    private int nextSample;

    boolean shouldDeferChunk(
            ChunkBatch batch,
            WorldApplyBudget budget,
            long elapsedThisTickNanos,
            int processedWorkThisTick
    ) {
        if (batch == null || budget == null || processedWorkThisTick <= 0) {
            return false;
        }
        if (this.sampleCount == 0) {
            return elapsedThisTickNanos >= OperationLoadPolicy.pressureBudgetNanos(budget.maxNanos());
        }
        long planningBudgetNanos = OperationLoadPolicy.pressureBudgetNanos(budget.maxNanos());
        long remainingNanos = planningBudgetNanos - Math.max(0L, elapsedThisTickNanos);
        if (remainingNanos <= MIN_REMAINING_NANOS) {
            return true;
        }
        long estimatedNanos = this.estimateNanos(batch);
        long guardNanos = Math.max(MIN_REMAINING_NANOS, Math.round(planningBudgetNanos * START_GUARD_FRACTION));
        return estimatedNanos + guardNanos > remainingNanos;
    }

    void recordChunk(ChunkBatch batch, long elapsedNanos) {
        int weight = weight(batch);
        if (weight <= 0 || elapsedNanos <= 0L) {
            return;
        }
        this.nanosPerWeightSamples[this.nextSample] = (double) elapsedNanos / weight;
        this.nextSample = (this.nextSample + 1) % WINDOW_SIZE;
        this.sampleCount = Math.min(WINDOW_SIZE, this.sampleCount + 1);
    }

    long estimateNanos(ChunkBatch batch) {
        int weight = weight(batch);
        if (weight <= 0 || this.sampleCount == 0) {
            return 0L;
        }
        return Math.max(1L, Math.round(this.percentile95NanosPerWeight() * weight));
    }

    private double percentile95NanosPerWeight() {
        double[] sorted = Arrays.copyOf(this.nanosPerWeightSamples, this.sampleCount);
        Arrays.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(this.sampleCount * 0.95D) - 1);
        return sorted[index];
    }

    private static int weight(ChunkBatch batch) {
        if (batch == null) {
            return 0;
        }
        int weight = Math.max(1, batch.totalPlacements());
        for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
            weight += sectionWeight(section);
        }
        weight += batch.sections().size() * 128;
        weight += batch.blockEntities().size() * 256;
        weight += BlockChangeApplier.entityOperationCount(batch.entityBatch()) * 512;
        return Math.max(1, weight);
    }

    private static int sectionWeight(PreparedSectionApplyBatch section) {
        if (section == null) {
            return 0;
        }
        if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
            return SectionChangeMask.ENTRY_COUNT;
        }
        return Math.max(256, section.changedCellCount());
    }
}
