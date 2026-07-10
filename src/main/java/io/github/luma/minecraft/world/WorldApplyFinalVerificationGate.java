package io.github.luma.minecraft.world;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;

/**
 * Holds prepared apply completion until target blocks are confirmed after all
 * delayed replay drains have run.
 */
final class WorldApplyFinalVerificationGate {

    private static final int MAX_RETRIES = 3;

    private final WorldApplyVerificationService verificationService = new WorldApplyVerificationService();
    private final WorldApplyVerificationRepairer verificationRepairer = new WorldApplyVerificationRepairer();
    private final List<ChunkBatch> batches = new ArrayList<>();
    private WorldApplyVerificationResult currentResult;
    private int currentRepaired;
    private int batchIndex;
    private int retryCount;

    void record(ChunkBatch batch) {
        if (batch != null) {
            this.batches.add(batch);
        }
    }

    boolean advance(
            ServerLevel level,
            WorldApplyBudget budget,
            long deadlineNanos,
            WorldApplyMetrics metrics,
            RedstoneReplayUpdateQueue redstoneUpdateQueue,
            WorldLightUpdateQueue lightUpdateQueue,
            WorldApplyPerformanceGovernor performanceGovernor
    ) {
        if (level == null || this.batches.isEmpty()) {
            return true;
        }

        while (this.batchIndex < this.batches.size() && System.nanoTime() < deadlineNanos) {
            ChunkBatch batch = this.batches.get(this.batchIndex);
            WorldApplyVerificationResult result = this.verifyAndRepair(
                    level,
                    batch,
                    budget,
                    deadlineNanos,
                    metrics,
                    redstoneUpdateQueue,
                    lightUpdateQueue,
                    performanceGovernor
            );
            if (result == null) {
                return false;
            }
            this.clearCurrent();
            if (result.mismatched() > 0) {
                this.retryCount += 1;
                if (this.retryCount > MAX_RETRIES) {
                    throw new IllegalStateException(
                            "World apply final verification failed after "
                                    + MAX_RETRIES
                                    + " retries for chunk "
                                    + batch.chunk().x()
                                    + ":"
                                    + batch.chunk().z()
                    );
                }
                return false;
            }
            this.retryCount = 0;
            this.batches.set(this.batchIndex, null);
            this.batchIndex += 1;
        }
        boolean complete = this.batchIndex >= this.batches.size();
        if (complete) {
            this.batches.clear();
        }
        return complete;
    }

    private WorldApplyVerificationResult verifyAndRepair(
            ServerLevel level,
            ChunkBatch batch,
            WorldApplyBudget budget,
            long deadlineNanos,
            WorldApplyMetrics metrics,
            RedstoneReplayUpdateQueue redstoneUpdateQueue,
            WorldLightUpdateQueue lightUpdateQueue,
            WorldApplyPerformanceGovernor performanceGovernor
    ) {
        if (batch == null || batch.totalPlacements() <= 0) {
            return WorldApplyVerificationResult.empty();
        }
        if (this.currentResult == null) {
            this.currentResult = this.verificationService.verify(level, batch);
            this.currentRepaired = 0;
            if (this.currentResult.hasRepairs()) {
                this.verificationRepairer.start(this.currentResult.repairSections());
            }
        }
        if (this.verificationRepairer.hasPending()) {
            long repairStartedAt = System.nanoTime();
            int repaired = this.verificationRepairer.drain(
                    level,
                    budget,
                    deadlineNanos,
                    metrics,
                    redstoneUpdateQueue,
                    lightUpdateQueue
            );
            long repairElapsedNanos = System.nanoTime() - repairStartedAt;
            performanceGovernor.recordWork(ApplyWorkKind.SPARSE_DIRECT, repaired, repairElapsedNanos);
            this.currentRepaired += repaired;
            if (this.verificationRepairer.hasPending()) {
                return null;
            }
        }
        return this.currentResult.withRepairOutcome(
                this.currentRepaired,
                this.currentResult.mismatched() - this.currentRepaired
        );
    }

    private void clearCurrent() {
        this.currentResult = null;
        this.currentRepaired = 0;
        this.verificationRepairer.clear();
    }
}
