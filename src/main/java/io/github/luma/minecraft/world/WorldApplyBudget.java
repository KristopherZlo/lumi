package io.github.luma.minecraft.world;

record WorldApplyBudget(
        int maxBlocks,
        long maxNanos,
        int maxNativeSections,
        int maxNativeCells,
        int maxRewriteSections
) {

    String summary() {
        return "maxBlocks=" + this.maxBlocks
                + ", maxNanos=" + this.maxNanos
                + ", maxNativeSections=" + this.maxNativeSections
                + ", maxNativeCells=" + this.maxNativeCells
                + ", maxRewriteSections=" + this.maxRewriteSections;
    }
}
