package io.github.luma.minecraft.world;

record DirectChunkApplyResult(
        int processedBlocks,
        int nextSectionIndex,
        int nextPlacementIndex,
        BlockCommitResult commitResult
) {

    static DirectChunkApplyResult none(int sectionIndex, int placementIndex) {
        return new DirectChunkApplyResult(
                0,
                Math.max(0, sectionIndex),
                Math.max(0, placementIndex),
                BlockCommitResult.direct(0, 0, 0, 0, 0, 0)
        );
    }
}
