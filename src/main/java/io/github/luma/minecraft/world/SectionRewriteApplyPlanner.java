package io.github.luma.minecraft.world;

import io.github.luma.minecraft.world.PersistentBlockStatePolicy.PersistentBlockState;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class SectionRewriteApplyPlanner {

    private final PersistentBlockStatePolicy blockStatePolicy;
    private final BlockPlacementUpdateDecider updateDecider;

    SectionRewriteApplyPlanner(
            PersistentBlockStatePolicy blockStatePolicy,
            BlockPlacementUpdateDecider updateDecider
    ) {
        this.blockStatePolicy = blockStatePolicy;
        this.updateDecider = updateDecider;
    }

    SectionRewriteApplyPlan plan(ServerLevel level, LevelChunkSection section, PreparedSectionApplyBatch batch) {
        if (batch.fullSection() && batch.changedCellCount() == SectionChangeMask.ENTRY_COUNT) {
            return this.planFullSection(section, batch);
        }
        return this.planPartialSection(level, section, batch);
    }

    private SectionRewriteApplyPlan planFullSection(LevelChunkSection section, PreparedSectionApplyBatch batch) {
        PlanBuilder builder = new PlanBuilder(true, batch.changedCellCount());
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        batch.buffer().changedCells().forEachSetCell(localIndex -> {
            BlockState targetState = this.normalizedTargetState(batch, localIndex);
            builder.addWrite(localIndex, targetState);

            BlockState currentState = section.getBlockState(
                    SectionChangeMask.localX(localIndex),
                    SectionChangeMask.localY(localIndex),
                    SectionChangeMask.localZ(localIndex)
            );
            if (currentState.equals(targetState)) {
                return;
            }
            builder.addChange(localIndex, currentState, targetState);
            addChangedCell(builder.changedCells(), mutablePos, batch, localIndex);
        });
        return builder.build();
    }

    private SectionRewriteApplyPlan planPartialSection(
            ServerLevel level,
            LevelChunkSection section,
            PreparedSectionApplyBatch batch
    ) {
        PlanBuilder builder = new PlanBuilder(false, batch.changedCellCount());
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        batch.buffer().changedCells().forEachSetCell(localIndex -> {
            int localX = SectionChangeMask.localX(localIndex);
            int localY = SectionChangeMask.localY(localIndex);
            int localZ = SectionChangeMask.localZ(localIndex);
            BlockState currentState = section.getBlockState(localX, localY, localZ);
            BlockState targetState = this.normalizedTargetState(batch, localIndex);
            mutablePos.set((batch.chunk().x() << 4) + localX, (batch.sectionY() << 4) + localY, (batch.chunk().z() << 4) + localZ);
            if (!this.updateDecider.requiresUpdate(level, mutablePos, currentState, targetState, null)) {
                return;
            }
            builder.addWrite(localIndex, targetState);
            builder.addChange(localIndex, currentState, targetState);
            builder.changedCells().add(SectionPos.sectionRelativePos(mutablePos));
        });
        return builder.build();
    }

    private BlockState normalizedTargetState(PreparedSectionApplyBatch batch, int localIndex) {
        CompoundTag rawBlockEntityTag = batch.buffer().blockEntityPlan().tagAt(localIndex);
        PersistentBlockState persistentState =
                this.blockStatePolicy.normalize(batch.buffer().targetStateAt(localIndex), rawBlockEntityTag);
        return persistentState.state();
    }

    private static void addChangedCell(
            ShortSet changedCells,
            BlockPos.MutableBlockPos mutablePos,
            PreparedSectionApplyBatch batch,
            int localIndex
    ) {
        mutablePos.set(
                (batch.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                (batch.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                (batch.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
        );
        changedCells.add(SectionPos.sectionRelativePos(mutablePos));
    }

    private static final class PlanBuilder {

        private final boolean rebuildsEntireSection;
        private final IntArrayList writeLocalIndexes;
        private final List<BlockState> writeStates;
        private final IntArrayList changedLocalIndexes;
        private final List<BlockState> currentStates;
        private final List<BlockState> changedTargetStates;
        private final ShortSet changedCells;

        private PlanBuilder(boolean rebuildsEntireSection, int expectedCells) {
            this.rebuildsEntireSection = rebuildsEntireSection;
            this.writeLocalIndexes = new IntArrayList(expectedCells);
            this.writeStates = new ArrayList<>(expectedCells);
            this.changedLocalIndexes = new IntArrayList(expectedCells);
            this.currentStates = new ArrayList<>(expectedCells);
            this.changedTargetStates = new ArrayList<>(expectedCells);
            this.changedCells = new ShortOpenHashSet(expectedCells);
        }

        private void addWrite(int localIndex, BlockState targetState) {
            this.writeLocalIndexes.add(localIndex);
            this.writeStates.add(targetState);
        }

        private void addChange(int localIndex, BlockState currentState, BlockState targetState) {
            this.changedLocalIndexes.add(localIndex);
            this.currentStates.add(currentState);
            this.changedTargetStates.add(targetState);
        }

        private ShortSet changedCells() {
            return this.changedCells;
        }

        private SectionRewriteApplyPlan build() {
            return new SectionRewriteApplyPlan(
                    this.rebuildsEntireSection,
                    this.writeLocalIndexes.toIntArray(),
                    this.writeStates.toArray(new BlockState[0]),
                    this.changedLocalIndexes.toIntArray(),
                    this.currentStates.toArray(new BlockState[0]),
                    this.changedTargetStates.toArray(new BlockState[0]),
                    this.changedCells
            );
        }
    }
}
