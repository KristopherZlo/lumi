package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyBudgetPlannerTest {

    private final WorldApplyBudgetPlanner planner = new WorldApplyBudgetPlanner();

    @Test
    void highThroughputBudgetsKeepLargeBlockLimitsAndExposeNativeCellCap() {
        WorldApplyBudget normal = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.NORMAL);
        WorldApplyBudget highThroughput = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.HISTORY_FAST);

        assertTrue(highThroughput.maxBlocks() > normal.maxBlocks());
        assertTrue(highThroughput.maxNanos() > normal.maxNanos());
        assertTrue(highThroughput.maxNativeSections() > normal.maxNativeSections());
        assertTrue(highThroughput.maxDirectSections() > normal.maxDirectSections());
        assertTrue(highThroughput.maxLightChecks() > normal.maxLightChecks());
        assertTrue(highThroughput.maxPreloadChunks() > normal.maxPreloadChunks());
        assertEquals(highThroughput.maxBlocks(), highThroughput.maxNativeCells());
        assertEquals(1, normal.maxRewriteSections());
        assertEquals(64, highThroughput.maxRewriteSections());
    }

    @Test
    void adaptiveScaleReducesBlockNativeAndTimeBudgetsTogether() {
        WorldApplyBudget fullScale = this.planner.plan(0.5D, 1.0D, WorldApplyProfile.HISTORY_FAST);
        WorldApplyBudget reduced = this.planner.plan(0.5D, 0.25D, WorldApplyProfile.HISTORY_FAST);

        assertTrue(reduced.maxBlocks() < fullScale.maxBlocks());
        assertTrue(reduced.maxNativeCells() < fullScale.maxNativeCells());
        assertTrue(reduced.maxNativeSections() < fullScale.maxNativeSections());
        assertTrue(reduced.maxRewriteSections() < fullScale.maxRewriteSections());
        assertTrue(reduced.maxDirectSections() < fullScale.maxDirectSections());
        assertTrue(reduced.maxLightChecks() < fullScale.maxLightChecks());
        assertTrue(reduced.maxPreloadChunks() < fullScale.maxPreloadChunks());
        assertTrue(reduced.maxNanos() < fullScale.maxNanos());
        assertTrue(reduced.maxRewriteSections() >= 1);
    }

    @Test
    void diagnosticTurboUsesLargerSparseAndLightBudgetsThanHistoryFast() {
        WorldApplyBudget historyFast = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.HISTORY_FAST);
        WorldApplyBudget turbo = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.DIAGNOSTIC_TURBO);

        assertTrue(turbo.maxBlocks() > historyFast.maxBlocks());
        assertTrue(turbo.maxNanos() > historyFast.maxNanos());
        assertTrue(turbo.maxDirectSections() > historyFast.maxDirectSections());
        assertTrue(turbo.maxLightChecks() > historyFast.maxLightChecks());
        assertTrue(turbo.sparseStepCap() > historyFast.sparseStepCap());
        assertTrue(turbo.maxPreloadChunks() > historyFast.maxPreloadChunks());
    }

    @Test
    void clampsProgressFractionForStableBudgetBounds() {
        WorldApplyBudget belowStart = this.planner.plan(-1.0D, 1.0D, WorldApplyProfile.NORMAL);
        WorldApplyBudget atStart = this.planner.plan(0.0D, 1.0D, WorldApplyProfile.NORMAL);
        WorldApplyBudget beyondEnd = this.planner.plan(2.0D, 1.0D, WorldApplyProfile.NORMAL);
        WorldApplyBudget atEnd = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.NORMAL);

        assertEquals(atStart, belowStart);
        assertEquals(atEnd, beyondEnd);
    }
}
