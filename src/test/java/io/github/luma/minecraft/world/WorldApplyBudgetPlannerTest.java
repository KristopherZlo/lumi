package io.github.luma.minecraft.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldApplyBudgetPlannerTest {

    private final WorldApplyBudgetPlanner planner = new WorldApplyBudgetPlanner();

    @Test
    void highThroughputBudgetsKeepLargeBlockLimitsAndExposeNativeCellCap() {
        WorldApplyBudget normal = this.planner.plan(1.0D, 1.0D, false);
        WorldApplyBudget highThroughput = this.planner.plan(1.0D, 1.0D, true);

        assertTrue(highThroughput.maxBlocks() > normal.maxBlocks());
        assertTrue(highThroughput.maxNativeSections() > normal.maxNativeSections());
        assertEquals(highThroughput.maxBlocks(), highThroughput.maxNativeCells());
        assertEquals(1, highThroughput.maxRewriteSections());
    }

    @Test
    void adaptiveScaleReducesBlockNativeAndTimeBudgetsTogether() {
        WorldApplyBudget fullScale = this.planner.plan(0.5D, 1.0D, true);
        WorldApplyBudget reduced = this.planner.plan(0.5D, 0.25D, true);

        assertTrue(reduced.maxBlocks() < fullScale.maxBlocks());
        assertTrue(reduced.maxNativeCells() < fullScale.maxNativeCells());
        assertTrue(reduced.maxNativeSections() < fullScale.maxNativeSections());
        assertTrue(reduced.maxNanos() < fullScale.maxNanos());
        assertEquals(1, reduced.maxRewriteSections());
    }

    @Test
    void clampsProgressFractionForStableBudgetBounds() {
        WorldApplyBudget belowStart = this.planner.plan(-1.0D, 1.0D, false);
        WorldApplyBudget atStart = this.planner.plan(0.0D, 1.0D, false);
        WorldApplyBudget beyondEnd = this.planner.plan(2.0D, 1.0D, false);
        WorldApplyBudget atEnd = this.planner.plan(1.0D, 1.0D, false);

        assertEquals(atStart, belowStart);
        assertEquals(atEnd, beyondEnd);
    }
}
