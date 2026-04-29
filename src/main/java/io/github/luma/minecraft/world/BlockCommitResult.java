package io.github.luma.minecraft.world;

record BlockCommitResult(
        int processedBlocks,
        int changedBlocks,
        int skippedBlocks,
        int directSections,
        int fallbackSections,
        int sectionPackets,
        int blockEntityPackets,
        int lightChecks,
        BlockCommitFallbackReason fallbackReason
) {

    static BlockCommitResult direct(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            int sectionPackets,
            int lightChecks
    ) {
        return new BlockCommitResult(
                processedBlocks,
                changedBlocks,
                skippedBlocks,
                processedBlocks > 0 ? 1 : 0,
                0,
                sectionPackets,
                0,
                lightChecks,
                BlockCommitFallbackReason.NONE
        );
    }

    static BlockCommitResult fallback(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            BlockCommitFallbackReason reason
    ) {
        return new BlockCommitResult(
                processedBlocks,
                changedBlocks,
                skippedBlocks,
                0,
                processedBlocks > 0 ? 1 : 0,
                0,
                0,
                0,
                reason == null ? BlockCommitFallbackReason.NONE : reason
        );
    }

    static BlockCommitResult blockEntityPackets(int packetCount) {
        return new BlockCommitResult(
                0,
                0,
                0,
                0,
                0,
                0,
                Math.max(0, packetCount),
                0,
                BlockCommitFallbackReason.NONE
        );
    }
}
