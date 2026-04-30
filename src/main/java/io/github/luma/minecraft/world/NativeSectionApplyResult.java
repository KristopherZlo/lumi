package io.github.luma.minecraft.world;

record NativeSectionApplyResult(
        int processedCells,
        boolean completedSection,
        BlockCommitResult commitResult
) {

    static NativeSectionApplyResult partial(int processedCells) {
        return new NativeSectionApplyResult(Math.max(0, processedCells), false, null);
    }

    static NativeSectionApplyResult completed(int processedCells, BlockCommitResult commitResult) {
        return new NativeSectionApplyResult(Math.max(0, processedCells), true, commitResult);
    }
}
