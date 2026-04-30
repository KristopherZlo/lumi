package io.github.luma.minecraft.world;

record BlockCommitResult(
        int processedBlocks,
        int changedBlocks,
        int skippedBlocks,
        int directSections,
        int fallbackSections,
        int rewriteSections,
        int rewriteCells,
        int rewriteFallbackSections,
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
        return result(processedBlocks, changedBlocks, skippedBlocks)
                .directSections(processedBlocks > 0 ? 1 : 0)
                .sectionPackets(sectionPackets)
                .lightChecks(lightChecks)
                .build();
    }

    static BlockCommitResult fallback(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            BlockCommitFallbackReason reason
    ) {
        return result(processedBlocks, changedBlocks, skippedBlocks)
                .fallbackSections(processedBlocks > 0 ? 1 : 0)
                .fallbackReason(reason)
                .build();
    }

    static BlockCommitResult blockEntityPackets(int packetCount) {
        return result(0, 0, 0)
                .blockEntityPackets(Math.max(0, packetCount))
                .build();
    }

    static BlockCommitResult rewriteSection(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            int sectionPackets,
            int lightChecks
    ) {
        return result(processedBlocks, changedBlocks, skippedBlocks)
                .rewriteSections(processedBlocks > 0 ? 1 : 0)
                .rewriteCells(changedBlocks)
                .sectionPackets(sectionPackets)
                .lightChecks(lightChecks)
                .build();
    }

    static BlockCommitResult rewriteFallback(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            BlockCommitFallbackReason reason
    ) {
        return result(processedBlocks, changedBlocks, skippedBlocks)
                .rewriteFallbackSections(processedBlocks > 0 ? 1 : 0)
                .fallbackReason(reason)
                .build();
    }

    static BlockCommitResult nativeSection(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            int sectionPackets,
            int blockEntityPackets,
            int lightChecks
    ) {
        return result(processedBlocks, changedBlocks, skippedBlocks)
                .nativeSections(processedBlocks > 0 ? 1 : 0)
                .nativeCells(changedBlocks)
                .sectionPackets(sectionPackets)
                .blockEntityPackets(Math.max(0, blockEntityPackets))
                .lightChecks(lightChecks)
                .build();
    }

    static BlockCommitResult nativeFallback(
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            BlockCommitFallbackReason reason
    ) {
        return result(processedBlocks, changedBlocks, skippedBlocks)
                .nativeFallbackSections(processedBlocks > 0 ? 1 : 0)
                .fallbackReason(reason)
                .build();
    }

    private static Builder result(int processedBlocks, int changedBlocks, int skippedBlocks) {
        return new Builder(processedBlocks, changedBlocks, skippedBlocks);
    }

    private static final class Builder {

        private final int processedBlocks;
        private final int changedBlocks;
        private final int skippedBlocks;
        private int directSections;
        private int fallbackSections;
        private int rewriteSections;
        private int rewriteCells;
        private int rewriteFallbackSections;
        private int nativeSections;
        private int nativeCells;
        private int nativeFallbackSections;
        private int sectionPackets;
        private int blockEntityPackets;
        private int lightChecks;
        private BlockCommitFallbackReason fallbackReason = BlockCommitFallbackReason.NONE;

        private Builder(int processedBlocks, int changedBlocks, int skippedBlocks) {
            this.processedBlocks = processedBlocks;
            this.changedBlocks = changedBlocks;
            this.skippedBlocks = skippedBlocks;
        }

        private Builder directSections(int directSections) {
            this.directSections = directSections;
            return this;
        }

        private Builder fallbackSections(int fallbackSections) {
            this.fallbackSections = fallbackSections;
            return this;
        }

        private Builder rewriteSections(int rewriteSections) {
            this.rewriteSections = rewriteSections;
            return this;
        }

        private Builder rewriteCells(int rewriteCells) {
            this.rewriteCells = rewriteCells;
            return this;
        }

        private Builder rewriteFallbackSections(int rewriteFallbackSections) {
            this.rewriteFallbackSections = rewriteFallbackSections;
            return this;
        }

        private Builder nativeSections(int nativeSections) {
            this.nativeSections = nativeSections;
            return this;
        }

        private Builder nativeCells(int nativeCells) {
            this.nativeCells = nativeCells;
            return this;
        }

        private Builder nativeFallbackSections(int nativeFallbackSections) {
            this.nativeFallbackSections = nativeFallbackSections;
            return this;
        }

        private Builder sectionPackets(int sectionPackets) {
            this.sectionPackets = sectionPackets;
            return this;
        }

        private Builder blockEntityPackets(int blockEntityPackets) {
            this.blockEntityPackets = blockEntityPackets;
            return this;
        }

        private Builder lightChecks(int lightChecks) {
            this.lightChecks = lightChecks;
            return this;
        }

        private Builder fallbackReason(BlockCommitFallbackReason fallbackReason) {
            this.fallbackReason = fallbackReason == null ? BlockCommitFallbackReason.NONE : fallbackReason;
            return this;
        }

        private BlockCommitResult build() {
            return new BlockCommitResult(
                    this.processedBlocks,
                    this.changedBlocks,
                    this.skippedBlocks,
                    this.directSections,
                    this.fallbackSections,
                    this.rewriteSections,
                    this.rewriteCells,
                    this.rewriteFallbackSections,
                    this.nativeSections,
                    this.nativeCells,
                    this.nativeFallbackSections,
                    this.sectionPackets,
                    this.blockEntityPackets,
                    this.lightChecks,
                    this.fallbackReason
            );
        }
    }
}
