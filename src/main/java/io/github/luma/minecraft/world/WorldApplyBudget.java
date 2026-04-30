package io.github.luma.minecraft.world;

record WorldApplyBudget(
        int maxBlocks,
        long maxNanos,
        int maxNativeSections,
        int maxNativeCells,
        int maxRewriteSections
) {
}
