package io.github.luma.minecraft.world;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Learns operation-local apply costs by work kind.
 */
final class ApplyCostModel {

    private static final int WINDOW_SIZE = 16;
    private static final double EWMA_ALPHA = 0.35D;

    private final Map<ApplyWorkKind, CostWindow> windows = new EnumMap<>(ApplyWorkKind.class);

    void record(ApplyWorkKind kind, int units, long elapsedNanos) {
        if (kind == null || kind == ApplyWorkKind.UNKNOWN || units <= 0 || elapsedNanos <= 0L) {
            return;
        }
        this.window(kind).record((double) elapsedNanos / units);
    }

    long estimateChunkNanos(ChunkBatch batch) {
        if (batch == null) {
            return 0L;
        }
        long estimate = 0L;
        int nativeCells = 0;
        int rewriteSections = 0;
        for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
            if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                rewriteSections += 1;
            } else {
                nativeCells += Math.max(1, section.changedCellCount());
            }
        }
        estimate += this.estimateNanos(ApplyWorkKind.SECTION_REWRITE, rewriteSections);
        estimate += this.estimateNanos(ApplyWorkKind.SECTION_NATIVE, nativeCells);
        estimate += this.estimateNanos(ApplyWorkKind.SPARSE_DIRECT, sparsePlacementCount(batch));
        estimate += this.estimateNanos(ApplyWorkKind.BLOCK_ENTITY, batch.blockEntities().size());
        estimate += this.estimateNanos(ApplyWorkKind.ENTITY, BlockChangeApplier.entityOperationCount(batch.entityBatch()));
        return estimate;
    }

    long estimateNanos(ApplyWorkKind kind, int units) {
        if (kind == null || units <= 0) {
            return 0L;
        }
        CostWindow window = this.windows.get(kind);
        if (window == null || !window.hasSamples()) {
            return 0L;
        }
        return Math.max(1L, Math.round(window.estimatedNanosPerUnit() * units));
    }

    boolean hasSamples() {
        for (CostWindow window : this.windows.values()) {
            if (window.hasSamples()) {
                return true;
            }
        }
        return false;
    }

    private CostWindow window(ApplyWorkKind kind) {
        return this.windows.computeIfAbsent(kind, ignored -> new CostWindow());
    }

    private static int sparsePlacementCount(ChunkBatch batch) {
        int count = 0;
        for (SectionBatch section : batch.sections().values()) {
            count += section.placementCount();
        }
        return count;
    }

    private static final class CostWindow {

        private final double[] samples = new double[WINDOW_SIZE];
        private int sampleCount;
        private int nextSample;
        private double ewma;

        private void record(double nanosPerUnit) {
            if (nanosPerUnit <= 0.0D) {
                return;
            }
            this.samples[this.nextSample] = nanosPerUnit;
            this.nextSample = (this.nextSample + 1) % WINDOW_SIZE;
            this.sampleCount = Math.min(WINDOW_SIZE, this.sampleCount + 1);
            this.ewma = this.ewma <= 0.0D
                    ? nanosPerUnit
                    : (this.ewma * (1.0D - EWMA_ALPHA)) + (nanosPerUnit * EWMA_ALPHA);
        }

        private boolean hasSamples() {
            return this.sampleCount > 0;
        }

        private double estimatedNanosPerUnit() {
            return Math.max(this.ewma, this.percentile95());
        }

        private double percentile95() {
            if (this.sampleCount <= 0) {
                return 0.0D;
            }
            double[] sorted = Arrays.copyOf(this.samples, this.sampleCount);
            Arrays.sort(sorted);
            int index = Math.max(0, (int) Math.ceil(this.sampleCount * 0.95D) - 1);
            return sorted[index];
        }
    }
}
