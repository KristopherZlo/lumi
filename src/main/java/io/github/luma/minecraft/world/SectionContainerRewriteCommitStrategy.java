package io.github.luma.minecraft.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

final class SectionContainerRewriteCommitStrategy {

    private final SectionRewriteApplyPlanner applyPlanner;
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
        this.applyPlanner = new SectionRewriteApplyPlanner(blockStatePolicy, updateDecider);
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

        SectionRewriteApplyPlan plan = this.applyPlanner.plan(level, section, batch);
        if (plan.isNoOp()) {
            return BlockCommitResult.rewriteSection(batch.changedCellCount(), 0, batch.changedCellCount(), 0, 0);
        }

        PalettedContainer<BlockState> replacement = plan.rebuildsEntireSection()
                ? section.getStates().recreate()
                : section.getStates().copy();
        plan.writeTo(replacement);

        if (!this.dataSwapper.swapData(section.getStates(), replacement)) {
            return this.fallback(level, batch, BlockCommitFallbackReason.REWRITE_UNAVAILABLE);
        }

        section.recalcBlockCounts();
        chunk.markUnsaved();
        this.heightmapUpdater.updateChangedColumns(chunk, section, batch.sectionY(), plan.changedLocalIndexes());
        SectionLightUpdateBatch lightBatch = new SectionLightUpdateBatch();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        for (int index = 0; index < plan.changedBlockCount(); index++) {
            int localIndex = plan.changedLocalIndexAt(index);
            mutablePos.set(
                    (batch.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                    (batch.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                    (batch.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
            );
            this.lightUpdatePlanner.plan(
                    lightBatch,
                    mutablePos,
                    plan.currentStateAt(index),
                    plan.changedTargetStateAt(index)
            );
        }

        int lightChecks = this.lightUpdatePlanner.apply(level, lightBatch);
        int sectionPackets = this.updateBroadcaster.broadcastSection(
                level,
                SectionPos.of(chunk.getPos(), batch.sectionY()),
                plan.changedCells(),
                section
        );
        return BlockCommitResult.rewriteSection(
                batch.changedCellCount(),
                plan.changedBlockCount(),
                plan.skippedBlockCount(batch.changedCellCount()),
                sectionPackets,
                lightChecks
        );
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

}
