package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

final class SectionContainerRewriteCommitStrategy {

    private final PersistentBlockStatePolicy blockStatePolicy;
    private final BlockPlacementUpdateDecider updateDecider;
    private final ChunkSectionUpdateBroadcaster updateBroadcaster;
    private final SectionNativeBlockCommitStrategy nativeFallback;
    private final PalettedContainerDataSwapper dataSwapper = new PalettedContainerDataSwapper();
    private final SectionRewritePreflight preflight;
    private final SectionHeightmapUpdater heightmapUpdater = new SectionHeightmapUpdater();
    private final SectionLightUpdatePlanner lightUpdatePlanner = new SectionLightUpdatePlanner();

    SectionContainerRewriteCommitStrategy(
            PersistentBlockStatePolicy blockStatePolicy,
            BlockPlacementUpdateDecider updateDecider,
            ChunkSectionUpdateBroadcaster updateBroadcaster,
            SectionNativeBlockCommitStrategy nativeFallback
    ) {
        this.blockStatePolicy = blockStatePolicy;
        this.updateDecider = updateDecider;
        this.updateBroadcaster = updateBroadcaster;
        this.nativeFallback = nativeFallback;
        this.preflight = new SectionRewritePreflight(blockStatePolicy);
    }

    BlockCommitResult apply(ServerLevel level, PreparedSectionApplyBatch batch) {
        if (level == null || batch == null || batch.changedCellCount() <= 0) {
            return BlockCommitResult.rewriteFallback(0, 0, 0, BlockCommitFallbackReason.EMPTY_BATCH);
        }
        if (batch.safetyProfile().path() != SectionApplyPath.SECTION_REWRITE) {
            return this.nativeFallback.apply(level, batch);
        }
        if (!this.dataSwapper.available()) {
            return this.fallback(level, batch, BlockCommitFallbackReason.REWRITE_UNAVAILABLE);
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(batch.chunk().x(), batch.chunk().z());
        if (chunk == null) {
            return this.fallback(level, batch, BlockCommitFallbackReason.CHUNK_NOT_LOADED);
        }

        int sectionIndex = chunk.getSectionIndexFromSectionY(batch.sectionY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return this.fallback(level, batch, BlockCommitFallbackReason.SECTION_OUT_OF_RANGE);
        }

        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            return this.fallback(level, batch, BlockCommitFallbackReason.SECTION_MISSING);
        }

        BlockCommitFallbackReason rejectionReason = this.preflight.rejectionReason(section, batch);
        if (rejectionReason != BlockCommitFallbackReason.NONE) {
            return this.fallback(level, batch, rejectionReason);
        }

        ApplyPlan plan = this.prepareApplyPlan(level, section, batch);
        if (plan.changedCells().isEmpty()) {
            return BlockCommitResult.rewriteSection(batch.changedCellCount(), 0, batch.changedCellCount(), 0, 0);
        }

        PalettedContainer<BlockState> replacement = batch.fullSection()
                ? section.getStates().recreate()
                : section.getStates().copy();
        for (CellMutation mutation : plan.mutations()) {
            int localIndex = mutation.localIndex();
            replacement.getAndSetUnchecked(
                    SectionChangeMask.localX(localIndex),
                    SectionChangeMask.localY(localIndex),
                    SectionChangeMask.localZ(localIndex),
                    mutation.targetState()
            );
        }

        if (!this.dataSwapper.swapData(section.getStates(), replacement)) {
            return this.fallback(level, batch, BlockCommitFallbackReason.REWRITE_UNAVAILABLE);
        }

        section.recalcBlockCounts();
        chunk.markUnsaved();
        this.heightmapUpdater.updateChangedColumns(chunk, section, batch.sectionY(), plan.localIndexes());
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        int lightChecks = 0;
        for (CellMutation mutation : plan.mutations()) {
            int localIndex = mutation.localIndex();
            mutablePos.set(
                    (batch.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                    (batch.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                    (batch.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
            );
            if (this.lightUpdatePlanner.check(level, mutablePos, mutation.currentState(), mutation.targetState())) {
                lightChecks += 1;
            }
        }

        int sectionPackets = this.updateBroadcaster.broadcastSection(
                level,
                SectionPos.of(chunk.getPos(), batch.sectionY()),
                plan.changedCells(),
                section
        );
        return BlockCommitResult.rewriteSection(
                batch.changedCellCount(),
                plan.mutations().size(),
                batch.changedCellCount() - plan.mutations().size(),
                sectionPackets,
                lightChecks
        );
    }

    private ApplyPlan prepareApplyPlan(ServerLevel level, LevelChunkSection section, PreparedSectionApplyBatch batch) {
        List<CellMutation> mutations = new ArrayList<>(batch.changedCellCount());
        ShortSet changedCells = new ShortOpenHashSet();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        batch.buffer().changedCells().forEachSetCell(localIndex -> {
            int localX = SectionChangeMask.localX(localIndex);
            int localY = SectionChangeMask.localY(localIndex);
            int localZ = SectionChangeMask.localZ(localIndex);
            BlockState currentState = section.getBlockState(localX, localY, localZ);
            CompoundTag rawBlockEntityTag = batch.buffer().blockEntityPlan().tagAt(localIndex);
            PersistentBlockStatePolicy.PersistentBlockState persistentState =
                    this.blockStatePolicy.normalize(batch.buffer().targetStateAt(localIndex), rawBlockEntityTag);
            BlockState targetState = persistentState.state();
            mutablePos.set((batch.chunk().x() << 4) + localX, (batch.sectionY() << 4) + localY, (batch.chunk().z() << 4) + localZ);
            if (!this.updateDecider.requiresUpdate(level, mutablePos, currentState, targetState, null)) {
                return;
            }
            mutations.add(new CellMutation(localIndex, currentState, targetState));
            changedCells.add(SectionPos.sectionRelativePos(mutablePos));
        });
        return new ApplyPlan(List.copyOf(mutations), changedCells);
    }

    private BlockCommitResult fallback(
            ServerLevel level,
            PreparedSectionApplyBatch batch,
            BlockCommitFallbackReason reason
    ) {
        BlockCommitResult rewriteFallback = BlockCommitResult.rewriteFallback(0, 0, 0, reason);
        return combine(rewriteFallback, this.nativeFallback.apply(level, batch));
    }

    private static BlockCommitResult combine(BlockCommitResult first, BlockCommitResult second) {
        return new BlockCommitResult(
                first.processedBlocks() + second.processedBlocks(),
                first.changedBlocks() + second.changedBlocks(),
                first.skippedBlocks() + second.skippedBlocks(),
                first.directSections() + second.directSections(),
                first.fallbackSections() + second.fallbackSections(),
                first.rewriteSections() + second.rewriteSections(),
                first.rewriteCells() + second.rewriteCells(),
                first.rewriteFallbackSections() + second.rewriteFallbackSections(),
                first.nativeSections() + second.nativeSections(),
                first.nativeCells() + second.nativeCells(),
                first.nativeFallbackSections() + second.nativeFallbackSections(),
                first.sectionPackets() + second.sectionPackets(),
                first.blockEntityPackets() + second.blockEntityPackets(),
                first.lightChecks() + second.lightChecks(),
                first.fallbackReason() == BlockCommitFallbackReason.NONE ? second.fallbackReason() : first.fallbackReason()
        );
    }

    private record ApplyPlan(List<CellMutation> mutations, ShortSet changedCells) {

        private List<Integer> localIndexes() {
            return this.mutations.stream().map(CellMutation::localIndex).toList();
        }
    }

    private record CellMutation(int localIndex, BlockState currentState, BlockState targetState) {
    }
}
