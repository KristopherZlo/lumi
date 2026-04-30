package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class SectionNativeBlockCommitStrategy {

    private final PersistentBlockStatePolicy blockStatePolicy;
    private final BlockPlacementUpdateDecider updateDecider;
    private final ChunkSectionUpdateBroadcaster updateBroadcaster;
    private final SectionHeightmapUpdater heightmapUpdater = new SectionHeightmapUpdater();
    private final SectionPoiUpdatePlanner poiUpdatePlanner = new SectionPoiUpdatePlanner();
    private final SectionLightUpdatePlanner lightUpdatePlanner = new SectionLightUpdatePlanner();

    SectionNativeBlockCommitStrategy(
            PersistentBlockStatePolicy blockStatePolicy,
            BlockPlacementUpdateDecider updateDecider,
            ChunkSectionUpdateBroadcaster updateBroadcaster
    ) {
        this.blockStatePolicy = blockStatePolicy;
        this.updateDecider = updateDecider;
        this.updateBroadcaster = updateBroadcaster;
    }

    BlockCommitResult apply(ServerLevel level, PreparedSectionApplyBatch batch) {
        if (level == null || batch == null || batch.buffer().changedCellCount() <= 0) {
            return BlockCommitResult.nativeFallback(0, 0, 0, BlockCommitFallbackReason.EMPTY_BATCH);
        }
        if (batch.safetyProfile().path() != SectionApplyPath.SECTION_NATIVE
                && batch.safetyProfile().path() != SectionApplyPath.SECTION_REWRITE) {
            return new VanillaBlockCommitStrategy(BlockCommitFallbackReason.NATIVE_REJECTED)
                    .apply(level, batch.toSectionBatch(), 0, batch.changedCellCount());
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(batch.chunk().x(), batch.chunk().z());
        if (chunk == null) {
            return new VanillaBlockCommitStrategy(BlockCommitFallbackReason.CHUNK_NOT_LOADED)
                    .apply(level, batch.toSectionBatch(), 0, batch.changedCellCount());
        }

        int sectionIndex = chunk.getSectionIndexFromSectionY(batch.sectionY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return new VanillaBlockCommitStrategy(BlockCommitFallbackReason.SECTION_OUT_OF_RANGE)
                    .apply(level, batch.toSectionBatch(), 0, batch.changedCellCount());
        }

        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            return new VanillaBlockCommitStrategy(BlockCommitFallbackReason.SECTION_MISSING)
                    .apply(level, batch.toSectionBatch(), 0, batch.changedCellCount());
        }

        ApplyCounters counters = new ApplyCounters();
        ShortSet changedCells = new ShortOpenHashSet();
        SectionLightUpdateBatch lightBatch = new SectionLightUpdateBatch();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        batch.buffer().changedCells().forEachSetCell(localIndex ->
                this.applyCell(level, chunk, section, batch, localIndex, mutablePos, changedCells, lightBatch, counters)
        );

        if (counters.changedBlocks > 0) {
            chunk.markUnsaved();
        }

        int lightChecks = this.lightUpdatePlanner.apply(level, lightBatch);
        int sectionPackets = this.updateBroadcaster.broadcastSection(
                level,
                SectionPos.of(chunk.getPos(), batch.sectionY()),
                changedCells,
                section
        );
        return BlockCommitResult.nativeSection(
                batch.changedCellCount(),
                counters.changedBlocks,
                counters.skippedBlocks,
                sectionPackets,
                counters.blockEntityPackets,
                lightChecks
        );
    }

    NativeSectionApplyResult applySlice(ServerLevel level, NativeSectionApplyCursor cursor, int maxCells) {
        if (cursor == null || cursor.isComplete() || maxCells <= 0) {
            return NativeSectionApplyResult.partial(0);
        }
        PreparedSectionApplyBatch batch = cursor.batch();
        if (level == null || batch.buffer().changedCellCount() <= 0) {
            cursor.advance(cursor.remainingCells());
            return NativeSectionApplyResult.completed(
                    0,
                    BlockCommitResult.nativeFallback(0, 0, 0, BlockCommitFallbackReason.EMPTY_BATCH)
            );
        }
        if (batch.safetyProfile().path() != SectionApplyPath.SECTION_NATIVE) {
            return this.completeWithFallback(level, cursor, BlockCommitFallbackReason.NATIVE_REJECTED);
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(batch.chunk().x(), batch.chunk().z());
        if (chunk == null) {
            return this.completeWithFallback(level, cursor, BlockCommitFallbackReason.CHUNK_NOT_LOADED);
        }

        int sectionIndex = chunk.getSectionIndexFromSectionY(batch.sectionY());
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return this.completeWithFallback(level, cursor, BlockCommitFallbackReason.SECTION_OUT_OF_RANGE);
        }

        LevelChunkSection section = chunk.getSection(sectionIndex);
        if (section == null) {
            return this.completeWithFallback(level, cursor, BlockCommitFallbackReason.SECTION_MISSING);
        }

        int processed = 0;
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        while (processed < maxCells && !cursor.isComplete()) {
            this.applyCell(level, chunk, section, cursor, mutablePos);
            cursor.advance();
            processed += 1;
        }

        if (!cursor.isComplete()) {
            return NativeSectionApplyResult.partial(processed);
        }

        if (!cursor.changedCells().isEmpty()) {
            chunk.markUnsaved();
        }
        int lightChecks = this.lightUpdatePlanner.apply(level, cursor.lightBatch());
        int sectionPackets = this.updateBroadcaster.broadcastSection(
                level,
                SectionPos.of(chunk.getPos(), batch.sectionY()),
                cursor.changedCells(),
                section
        );
        return NativeSectionApplyResult.completed(processed, cursor.completedNativeResult(sectionPackets, lightChecks));
    }

    private NativeSectionApplyResult completeWithFallback(
            ServerLevel level,
            NativeSectionApplyCursor cursor,
            BlockCommitFallbackReason reason
    ) {
        BlockCommitResult result = new VanillaBlockCommitStrategy(reason).apply(
                level,
                cursor.batch().toSectionBatch(),
                cursor.nextCellOrdinal(),
                cursor.remainingCells()
        );
        result = withAdditionalLightChecks(result, this.lightUpdatePlanner.apply(level, cursor.lightBatch()));
        cursor.advance(result.processedBlocks());
        return NativeSectionApplyResult.completed(result.processedBlocks(), result);
    }

    private void applyCell(
            ServerLevel level,
            LevelChunk chunk,
            LevelChunkSection section,
            PreparedSectionApplyBatch batch,
            int localIndex,
            BlockPos.MutableBlockPos mutablePos,
            ShortSet changedCells,
            SectionLightUpdateBatch lightBatch,
            ApplyCounters counters
    ) {
        int localX = SectionChangeMask.localX(localIndex);
        int localY = SectionChangeMask.localY(localIndex);
        int localZ = SectionChangeMask.localZ(localIndex);
        BlockState rawTargetState = batch.buffer().targetStateAt(localIndex);
        CompoundTag rawBlockEntityTag = batch.buffer().blockEntityPlan().tagAt(localIndex);
        PersistentBlockStatePolicy.PersistentBlockState persistentState =
                this.blockStatePolicy.normalize(rawTargetState, rawBlockEntityTag);
        BlockState targetState = persistentState.state();
        CompoundTag targetBlockEntityTag = persistentState.blockEntityTag();
        BlockState currentState = section.getBlockState(localX, localY, localZ);
        mutablePos.set((batch.chunk().x() << 4) + localX, (batch.sectionY() << 4) + localY, (batch.chunk().z() << 4) + localZ);
        if (!this.updateDecider.requiresUpdate(level, mutablePos, currentState, targetState, targetBlockEntityTag)) {
            counters.skippedBlocks += 1;
            return;
        }

        if (currentState.hasBlockEntity()) {
            level.removeBlockEntity(mutablePos);
        }
        section.setBlockState(localX, localY, localZ, targetState, false);
        this.heightmapUpdater.update(chunk, batch.sectionY(), localIndex, targetState);
        this.poiUpdatePlanner.update(level, mutablePos, currentState, targetState);
        this.lightUpdatePlanner.plan(lightBatch, mutablePos, currentState, targetState);
        changedCells.add(SectionPos.sectionRelativePos(mutablePos));
        counters.changedBlocks += 1;

        if (targetState.hasBlockEntity()) {
            counters.blockEntityPackets += this.createTargetBlockEntity(level, mutablePos.immutable(), targetState, targetBlockEntityTag);
        }
    }

    private void applyCell(
            ServerLevel level,
            LevelChunk chunk,
            LevelChunkSection section,
            NativeSectionApplyCursor cursor,
            BlockPos.MutableBlockPos mutablePos
    ) {
        PreparedSectionApplyBatch batch = cursor.batch();
        int localIndex = cursor.nextLocalIndex();
        int localX = SectionChangeMask.localX(localIndex);
        int localY = SectionChangeMask.localY(localIndex);
        int localZ = SectionChangeMask.localZ(localIndex);
        BlockState rawTargetState = batch.buffer().targetStateAt(localIndex);
        CompoundTag rawBlockEntityTag = batch.buffer().blockEntityPlan().tagAt(localIndex);
        PersistentBlockStatePolicy.PersistentBlockState persistentState =
                this.blockStatePolicy.normalize(rawTargetState, rawBlockEntityTag);
        BlockState targetState = persistentState.state();
        CompoundTag targetBlockEntityTag = persistentState.blockEntityTag();
        BlockState currentState = section.getBlockState(localX, localY, localZ);
        mutablePos.set((batch.chunk().x() << 4) + localX, (batch.sectionY() << 4) + localY, (batch.chunk().z() << 4) + localZ);
        if (!this.updateDecider.requiresUpdate(level, mutablePos, currentState, targetState, targetBlockEntityTag)) {
            cursor.recordSkipped();
            return;
        }

        if (currentState.hasBlockEntity()) {
            level.removeBlockEntity(mutablePos);
        }
        section.setBlockState(localX, localY, localZ, targetState, false);
        this.heightmapUpdater.update(chunk, batch.sectionY(), localIndex, targetState);
        this.poiUpdatePlanner.update(level, mutablePos, currentState, targetState);
        this.lightUpdatePlanner.plan(cursor.lightBatch(), mutablePos, currentState, targetState);
        int blockEntityPackets = 0;
        if (targetState.hasBlockEntity()) {
            blockEntityPackets = this.createTargetBlockEntity(level, mutablePos.immutable(), targetState, targetBlockEntityTag);
        }
        cursor.recordChanged(SectionPos.sectionRelativePos(mutablePos), blockEntityPackets);
    }

    private int createTargetBlockEntity(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            CompoundTag blockEntityTag
    ) {
        BlockEntity blockEntity = blockEntityTag == null
                ? this.newBlockEntity(pos, state)
                : BlockEntity.loadStatic(pos, state, blockEntityTag.copy(), level.registryAccess());
        if (blockEntity == null) {
            return 0;
        }
        level.setBlockEntity(blockEntity);
        return this.updateBroadcaster.broadcastBlockEntity(level, blockEntity);
    }

    private BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof EntityBlock entityBlock)) {
            return null;
        }
        return entityBlock.newBlockEntity(pos, state);
    }

    private static final class ApplyCounters {

        private int changedBlocks;
        private int skippedBlocks;
        private int blockEntityPackets;
    }

    private static BlockCommitResult withAdditionalLightChecks(BlockCommitResult result, int lightChecks) {
        if (result == null || lightChecks <= 0) {
            return result;
        }
        return new BlockCommitResult(
                result.processedBlocks(),
                result.changedBlocks(),
                result.skippedBlocks(),
                result.directSections(),
                result.fallbackSections(),
                result.rewriteSections(),
                result.rewriteCells(),
                result.rewriteFallbackSections(),
                result.nativeSections(),
                result.nativeCells(),
                result.nativeFallbackSections(),
                result.sectionPackets(),
                result.blockEntityPackets(),
                result.lightChecks() + lightChecks,
                result.fallbackReason()
        );
    }
}
