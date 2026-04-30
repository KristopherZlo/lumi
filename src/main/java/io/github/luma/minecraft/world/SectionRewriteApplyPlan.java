package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

final class SectionRewriteApplyPlan {

    private final boolean rebuildsEntireSection;
    private final int[] writeLocalIndexes;
    private final BlockState[] writeStates;
    private final int[] changedLocalIndexes;
    private final BlockState[] currentStates;
    private final BlockState[] changedTargetStates;
    private final ShortSet changedCells;

    SectionRewriteApplyPlan(
            boolean rebuildsEntireSection,
            int[] writeLocalIndexes,
            BlockState[] writeStates,
            int[] changedLocalIndexes,
            BlockState[] currentStates,
            BlockState[] changedTargetStates,
            ShortSet changedCells
    ) {
        if (writeLocalIndexes.length != writeStates.length) {
            throw new IllegalArgumentException("write indexes and states must have the same length");
        }
        if (changedLocalIndexes.length != currentStates.length
                || changedLocalIndexes.length != changedTargetStates.length) {
            throw new IllegalArgumentException("changed indexes and states must have the same length");
        }
        this.rebuildsEntireSection = rebuildsEntireSection;
        this.writeLocalIndexes = writeLocalIndexes;
        this.writeStates = writeStates;
        this.changedLocalIndexes = changedLocalIndexes;
        this.currentStates = currentStates;
        this.changedTargetStates = changedTargetStates;
        this.changedCells = changedCells;
    }

    boolean rebuildsEntireSection() {
        return this.rebuildsEntireSection;
    }

    boolean isNoOp() {
        return this.changedLocalIndexes.length == 0;
    }

    int changedBlockCount() {
        return this.changedLocalIndexes.length;
    }

    int skippedBlockCount(int requestedCells) {
        return Math.max(0, requestedCells - this.changedBlockCount());
    }

    int writeCount() {
        return this.writeLocalIndexes.length;
    }

    int[] changedLocalIndexes() {
        return this.changedLocalIndexes;
    }

    ShortSet changedCells() {
        return this.changedCells;
    }

    int changedLocalIndexAt(int index) {
        return this.changedLocalIndexes[index];
    }

    BlockState currentStateAt(int index) {
        return this.currentStates[index];
    }

    BlockState changedTargetStateAt(int index) {
        return this.changedTargetStates[index];
    }

    void writeTo(PalettedContainer<BlockState> replacement) {
        for (int index = 0; index < this.writeLocalIndexes.length; index++) {
            int localIndex = this.writeLocalIndexes[index];
            replacement.getAndSetUnchecked(
                    SectionChangeMask.localX(localIndex),
                    SectionChangeMask.localY(localIndex),
                    SectionChangeMask.localZ(localIndex),
                    this.writeStates[index]
            );
        }
    }
}
