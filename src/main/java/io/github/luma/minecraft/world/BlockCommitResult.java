package io.github.luma.minecraft.world;

record BlockCommitResult(
        int processedBlocks,
        int changedBlocks,
        int skippedBlocks,
        int directSections,
        int fallbackSections,
        int nativeSections,
        int nativeCells,
        int nativeFallbackSections,
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
                0,
                0,
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
                0,
                0,
                0,
                Math.max(0, packetCount),
                0,
                BlockCommitFallbackReason.NONE
        );
    }

    static BlockCommitResult nativeSection(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            int sectionPackets,
            int blockEntityPackets,
            int lightChecks
    ) {
        return new BlockCommitResult(
                processedBlocks,
                changedBlocks,
                skippedBlocks,
                0,
                0,
                processedBlocks > 0 ? 1 : 0,
                changedBlocks,
                0,
                sectionPackets,
                Math.max(0, blockEntityPackets),
                lightChecks,
                BlockCommitFallbackReason.NONE
        );
    }

    static BlockCommitResult nativeFallback(
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
                0,
                0,
                0,
                processedBlocks > 0 ? 1 : 0,
                0,
                0,
                0,
                reason == null ? BlockCommitFallbackReason.NONE : reason
        );
    }
}
