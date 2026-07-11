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
        assertTrue(highThroughput.maxBlockEntities() > normal.maxBlockEntities());
        assertTrue(highThroughput.maxEntityOperations() > normal.maxEntityOperations());
        assertEquals(highThroughput.maxBlocks(), highThroughput.maxNativeCells());
        assertEquals(1, normal.maxRewriteSections());
        assertEquals(128, highThroughput.maxRewriteSections());
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
    void maximumProfileUsesForegroundRestoreBudgets() {
        WorldApplyBudget turbo = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.DIAGNOSTIC_TURBO);
        WorldApplyBudget maximum = this.planner.plan(1.0D, 1.0D, WorldApplyProfile.MAXIMUM);

        assertTrue(maximum.maxBlocks() > turbo.maxBlocks());
        assertTrue(maximum.maxNanos() > turbo.maxNanos());
        assertTrue(maximum.maxNativeSections() > turbo.maxNativeSections());
        assertTrue(maximum.maxRewriteSections() > turbo.maxRewriteSections());
        assertTrue(maximum.maxDirectSections() > turbo.maxDirectSections());
        assertTrue(maximum.maxLightChecks() > turbo.maxLightChecks());
        assertTrue(maximum.maxRedstoneUpdates() > turbo.maxRedstoneUpdates());
        assertTrue(maximum.maxPreloadChunks() > turbo.maxPreloadChunks());
        assertTrue(maximum.maxBlockEntities() > turbo.maxBlockEntities());
        assertTrue(maximum.maxEntityOperations() > turbo.maxEntityOperations());
    }

    @Test
    void runtimeProfilesAvoidSynchronousChunkLoadsByDefault() {
        assertEquals(0, this.planner.plan(1.0D, 1.0D, WorldApplyProfile.NORMAL).maxSyncChunkLoads());
        assertEquals(1, this.planner.plan(1.0D, 1.0D, WorldApplyProfile.HISTORY_FAST).maxSyncChunkLoads());
        assertEquals(0, this.planner.plan(1.0D, 1.0D, WorldApplyProfile.DIAGNOSTIC_TURBO).maxSyncChunkLoads());
        assertEquals(0, this.planner.plan(1.0D, 1.0D, WorldApplyProfile.MAXIMUM).maxSyncChunkLoads());
    }

    @Test
    void fastProfilesKeepSafeMinimumDirectAndTimeBudgetsAfterDownscale() {
        WorldApplyBudget normal = this.planner.plan(0.0D, 0.25D, WorldApplyProfile.NORMAL);
        WorldApplyBudget historyFast = this.planner.plan(0.0D, 0.25D, WorldApplyProfile.HISTORY_FAST);
        WorldApplyBudget turbo = this.planner.plan(0.0D, 0.25D, WorldApplyProfile.DIAGNOSTIC_TURBO);
        WorldApplyBudget maximum = this.planner.plan(0.0D, 0.25D, WorldApplyProfile.MAXIMUM);

        assertEquals(1, normal.maxDirectSections());
        assertEquals(250_000L, normal.maxNanos());
        assertEquals(64, historyFast.maxDirectSections());
        assertEquals(16_000_000L, historyFast.maxNanos());
        assertEquals(128, turbo.maxDirectSections());
        assertEquals(32_000_000L, turbo.maxNanos());
        assertEquals(512, maximum.maxDirectSections());
        assertEquals(20_000_000L, maximum.maxNanos());
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
