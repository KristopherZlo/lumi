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
        if (batch.safetyProfile().path() != SectionApplyPath.SECTION_NATIVE) {
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
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        batch.buffer().changedCells().forEachSetCell(localIndex ->
                this.applyCell(level, chunk, section, batch, localIndex, mutablePos, changedCells, counters)
        );

        if (counters.changedBlocks > 0) {
            chunk.markUnsaved();
        }

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
                counters.lightChecks
        );
    }

    private void applyCell(
            ServerLevel level,
            LevelChunk chunk,
            LevelChunkSection section,
            PreparedSectionApplyBatch batch,
            int localIndex,
            BlockPos.MutableBlockPos mutablePos,
            ShortSet changedCells,
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
        if (this.lightUpdatePlanner.check(level, mutablePos, currentState, targetState)) {
            counters.lightChecks += 1;
        }
        changedCells.add(SectionPos.sectionRelativePos(mutablePos));
        counters.changedBlocks += 1;

        if (targetState.hasBlockEntity()) {
            counters.blockEntityPackets += this.createTargetBlockEntity(level, mutablePos.immutable(), targetState, targetBlockEntityTag);
        }
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
        private int lightChecks;
    }
}
