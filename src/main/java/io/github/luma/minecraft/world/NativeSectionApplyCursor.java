package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;

final class NativeSectionApplyCursor {

    private final PreparedSectionApplyBatch batch;
    private final int[] localIndexes;
    private final ShortSet changedCells = new ShortOpenHashSet();
    private int nextOrdinal;
    private int changedBlocks;
    private int skippedBlocks;
    private int blockEntityPackets;
    private int lightChecks;

    NativeSectionApplyCursor(PreparedSectionApplyBatch batch) {
        if (batch == null) {
            throw new IllegalArgumentException("batch is required");
        }
        this.batch = batch;
        this.localIndexes = localIndexes(batch);
    }

    PreparedSectionApplyBatch batch() {
        return this.batch;
    }

    boolean isFor(PreparedSectionApplyBatch candidate) {
        return this.batch == candidate;
    }

    boolean isRewrite() {
        return this.batch.safetyProfile().path() == SectionApplyPath.SECTION_REWRITE;
    }

    boolean isComplete() {
        return this.nextOrdinal >= this.localIndexes.length;
    }

    int remainingCells() {
        return Math.max(0, this.localIndexes.length - this.nextOrdinal);
    }

    int nextCellOrdinal() {
        return this.nextOrdinal;
    }

    int nextLocalIndex() {
        return this.localIndexes[this.nextOrdinal];
    }

    void recordChanged(short relativeCell, int blockEntityPackets, boolean lightCheck) {
        this.changedCells.add(relativeCell);
        this.changedBlocks += 1;
        this.blockEntityPackets += Math.max(0, blockEntityPackets);
        if (lightCheck) {
            this.lightChecks += 1;
        }
    }

    void recordSkipped() {
        this.skippedBlocks += 1;
    }

    void advance() {
        this.nextOrdinal += 1;
    }

    void advance(int cells) {
        this.nextOrdinal = Math.min(this.localIndexes.length, this.nextOrdinal + Math.max(0, cells));
    }

    ShortSet changedCells() {
        return this.changedCells;
    }

    BlockCommitResult completedNativeResult(int sectionPackets) {
        return BlockCommitResult.nativeSection(
                this.localIndexes.length,
                this.changedBlocks,
                this.skippedBlocks,
                sectionPackets,
                this.blockEntityPackets,
                this.lightChecks
        );
    }

    private static int[] localIndexes(PreparedSectionApplyBatch batch) {
        int[] indexes = new int[batch.changedCellCount()];
        int[] next = new int[] {0};
        batch.buffer().changedCells().forEachSetCell(localIndex -> {
            indexes[next[0]] = localIndex;
            next[0] += 1;
        });
        return indexes;
    }
}
