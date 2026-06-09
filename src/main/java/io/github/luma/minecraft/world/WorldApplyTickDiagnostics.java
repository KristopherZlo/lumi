package io.github.luma.minecraft.world;

import net.minecraft.world.level.block.state.BlockState;

final class WorldApplyTickDiagnostics {

    TickCounters startTick(WorldApplyMetrics metrics) {
        return new TickCounters(metrics);
    }

    String applyDetail(String preparationMarker, String detail) {
        if (preparationMarker == null || preparationMarker.isBlank()) {
            return detail;
        }
        return preparationMarker + "; " + detail;
    }

    String chunkId(ChunkBatch batch) {
        if (batch == null) {
            return "none";
        }
        return batch.chunk().x() + ":" + batch.chunk().z();
    }

    ChunkShape chunkShape(ChunkBatch batch) {
        if (batch == null) {
            return new ChunkShape(0, 0, 0, 0, 0);
        }
        int[] counts = new int[5];
        for (PreparedSectionApplyBatch section : batch.orderedNativeSections()) {
            int before = counts[0] + counts[1];
            this.addNativeTargets(counts, section);
            int added = counts[0] + counts[1] - before;
            if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                counts[4] += added;
            } else {
                counts[3] += added;
            }
        }
        for (SectionBatch section : batch.orderedSections()) {
            if (section.placements() == null) {
                continue;
            }
            for (PreparedBlockPlacement placement : section.placements()) {
                this.addTarget(counts, placement.state());
                counts[2] += 1;
            }
        }
        return new ChunkShape(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    int rewriteSectionCount(ChunkBatch batch) {
        if (batch == null) {
            return 0;
        }
        int count = 0;
        for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
            if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                count += 1;
            }
        }
        return count;
    }

    int nativeCellCount(ChunkBatch batch) {
        if (batch == null) {
            return 0;
        }
        int count = 0;
        for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
            if (section.safetyProfile().path() == SectionApplyPath.SECTION_NATIVE) {
                count += section.changedCellCount();
            }
        }
        return count;
    }

    int rewriteCellCount(ChunkBatch batch) {
        if (batch == null) {
            return 0;
        }
        int count = 0;
        for (PreparedSectionApplyBatch section : batch.nativeSections().values()) {
            if (section.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE) {
                count += section.changedCellCount();
            }
        }
        return count;
    }

    int sparsePlacementCount(ChunkBatch batch) {
        if (batch == null) {
            return 0;
        }
        int count = 0;
        for (SectionBatch section : batch.sections().values()) {
            count += section.placementCount();
        }
        return count;
    }

    String commitSummary(BlockCommitResult result) {
        if (result == null) {
            return "partial";
        }
        return "processed=" + result.processedBlocks()
                + ", changed=" + result.changedBlocks()
                + ", skipped=" + result.skippedBlocks()
                + ", rewriteSections=" + result.rewriteSections()
                + ", nativeSections=" + result.nativeSections()
                + ", directSections=" + result.directSections()
                + ", fallbackSections=" + (result.fallbackSections()
                        + result.nativeFallbackSections()
                        + result.rewriteFallbackSections())
                + ", packets=" + result.sectionPackets()
                + ", blockEntityPackets=" + result.blockEntityPackets()
                + ", lightChecks=" + result.lightChecks()
                + ", reason=" + result.fallbackReason();
    }

    private void addNativeTargets(int[] counts, PreparedSectionApplyBatch section) {
        if (section == null || section.buffer() == null) {
            return;
        }
        section.buffer().changedCells().forEachSetCell(localIndex ->
                this.addTarget(counts, section.buffer().targetStateAt(localIndex))
        );
    }

    private void addTarget(int[] counts, BlockState state) {
        if (state != null && state.isAir()) {
            counts[1] += 1;
        } else {
            counts[0] += 1;
        }
    }

    record ChunkShape(
            int setTargets,
            int deleteTargets,
            int sparseTargets,
            int nativeTargets,
            int rewriteTargets
    ) {
    }

    static final class TickCounters {

        private final int startProcessedBlocks;
        private final int startRewriteSections;
        private final int startNativeSections;
        private final int startFallbackSections;
        private final int startLightChecks;
        private int workUnits;
        private int nativeSections;
        private int nativeCells;
        private int rewriteSections;
        private int directSections;
        private int startedChunks;
        private int finishedChunks;

        private TickCounters(WorldApplyMetrics metrics) {
            this.startProcessedBlocks = metrics.processedBlocks();
            this.startRewriteSections = metrics.rewriteSections();
            this.startNativeSections = metrics.nativeSections();
            this.startFallbackSections = metrics.fallbackSections();
            this.startLightChecks = metrics.lightChecks();
        }

        int workUnits() {
            return this.workUnits;
        }

        int nativeSections() {
            return this.nativeSections;
        }

        int nativeCells() {
            return this.nativeCells;
        }

        int rewriteSections() {
            return this.rewriteSections;
        }

        int directSections() {
            return this.directSections;
        }

        void recordWork(
                int workUnits,
                int nativeSections,
                int nativeCells,
                int rewriteSections,
                int directSections
        ) {
            this.workUnits += workUnits;
            this.nativeSections += nativeSections;
            this.nativeCells += nativeCells;
            this.rewriteSections += rewriteSections;
            this.directSections += directSections;
        }

        void recordChunkStarted() {
            this.startedChunks += 1;
        }

        void recordChunkFinished() {
            this.finishedChunks += 1;
        }

        String tickDetail(
                String stopReason,
                WorldApplyMetrics metrics,
                String currentBatch,
                boolean dispatcherPending,
                int lightPending,
                int redstonePending
        ) {
            return "stop=" + stopReason
                    + ", workThisTick=" + this.workUnits
                    + ", nativeSectionsThisTick=" + this.nativeSections
                    + ", nativeCellsThisTick=" + this.nativeCells
                    + ", rewriteSectionsThisTick=" + this.rewriteSections
                    + ", directSectionsThisTick=" + this.directSections
                    + ", chunksStarted=" + this.startedChunks
                    + ", chunksFinished=" + this.finishedChunks
                    + ", processedDelta=" + (metrics.processedBlocks() - this.startProcessedBlocks)
                    + ", rewriteSectionsDelta=" + (metrics.rewriteSections() - this.startRewriteSections)
                    + ", nativeSectionsDelta=" + (metrics.nativeSections() - this.startNativeSections)
                    + ", fallbackSectionsDelta=" + (metrics.fallbackSections() - this.startFallbackSections)
                    + ", lightChecksDelta=" + (metrics.lightChecks() - this.startLightChecks)
                    + ", currentBatch=" + currentBatch
                    + ", dispatcherPending=" + dispatcherPending
                    + ", lightPending=" + lightPending
                    + ", redstonePending=" + redstonePending;
        }

    }
}
