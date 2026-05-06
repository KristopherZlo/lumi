package io.github.luma.minecraft.world;

record WorldApplyBudget(
        int maxBlocks,
        long maxNanos,
        int maxNativeSections,
        int maxNativeCells,
        int maxRewriteSections,
        int maxDirectSections,
        int maxLightChecks,
        int maxRedstoneUpdates,
        int sparseStepCap,
        int maxPreloadChunks
) {

    String summary() {
        return "maxBlocks=" + this.maxBlocks
                + ", maxNanos=" + this.maxNanos
                + ", maxNativeSections=" + this.maxNativeSections
                + ", maxNativeCells=" + this.maxNativeCells
                + ", maxRewriteSections=" + this.maxRewriteSections
                + ", maxDirectSections=" + this.maxDirectSections
                + ", maxLightChecks=" + this.maxLightChecks
                + ", maxRedstoneUpdates=" + this.maxRedstoneUpdates
                + ", sparseStepCap=" + this.sparseStepCap
                + ", maxPreloadChunks=" + this.maxPreloadChunks;
    }
}
