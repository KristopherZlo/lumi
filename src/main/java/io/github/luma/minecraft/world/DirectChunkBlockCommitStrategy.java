package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

final class DirectChunkBlockCommitStrategy {

    private final PersistentBlockStatePolicy blockStatePolicy;
    private final BlockPlacementUpdateDecider updateDecider;
    private final ChunkSectionUpdateBroadcaster updateBroadcaster;
    private final DirectSectionBlockCommitStrategy sectionFallback;
    private final SectionLightUpdatePlanner lightUpdatePlanner = new SectionLightUpdatePlanner();
    private final SparseDeleteFastPathClassifier deleteFastPathClassifier = new SparseDeleteFastPathClassifier();

    DirectChunkBlockCommitStrategy(
            PersistentBlockStatePolicy blockStatePolicy,
            BlockPlacementUpdateDecider updateDecider,
            ChunkSectionUpdateBroadcaster updateBroadcaster,
            DirectSectionBlockCommitStrategy sectionFallback
    ) {
        this.blockStatePolicy = blockStatePolicy;
        this.updateDecider = updateDecider;
        this.updateBroadcaster = updateBroadcaster;
        this.sectionFallback = sectionFallback;
    }

    DirectChunkApplyResult apply(
            ServerLevel level,
            ChunkBatch batch,
            int startSectionIndex,
            int startPlacementIndex,
            int maxBlocks,
            int maxSections
    ) {
        if (level == null || batch == null || maxBlocks <= 0 || maxSections <= 0) {
            return DirectChunkApplyResult.none(startSectionIndex, startPlacementIndex);
        }

        List<SectionBatch> sections = batch.orderedSections();
        if (sections.isEmpty() || startSectionIndex >= sections.size()) {
            return DirectChunkApplyResult.none(startSectionIndex, startPlacementIndex);
        }

        LevelChunk chunk = this.loadedChunk(level, batch);
        if (chunk == null) {
            return this.applyFallbackSection(level, sections, startSectionIndex, startPlacementIndex, maxBlocks);
        }

        int sectionIndex = Math.max(0, startSectionIndex);
        int placementIndex = Math.max(0, startPlacementIndex);
        int processedBlocks = 0;
        int changedBlocks = 0;
        int skippedBlocks = 0;
        int directSections = 0;
        Map<Integer, SectionUpdate> sectionUpdates = new LinkedHashMap<>();
        SectionLightUpdateBatch lightBatch = new SectionLightUpdateBatch();
        ChunkHeightmapUpdatePlan heightmapPlan = new ChunkHeightmapUpdatePlan();
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();

        while (sectionIndex < sections.size()
                && processedBlocks < maxBlocks
                && directSections < maxSections) {
            SectionBatch sectionBatch = sections.get(sectionIndex);
            if (sectionBatch.placementCount() <= 0 || placementIndex >= sectionBatch.placementCount()) {
                sectionIndex += 1;
                placementIndex = 0;
                continue;
            }

            int sectionArrayIndex = chunk.getSectionIndexFromSectionY(sectionBatch.sectionY());
            if (sectionArrayIndex < 0 || sectionArrayIndex >= chunk.getSections().length) {
                return this.combineWithFallback(
                        level,
                        sections,
                        sectionIndex,
                        placementIndex,
                        maxBlocks - processedBlocks,
                        processedBlocks,
                        changedBlocks,
                        skippedBlocks,
                        directSections,
                        chunk,
                        sectionUpdates,
                        lightBatch,
                        heightmapPlan
                );
            }
            LevelChunkSection section = chunk.getSection(sectionArrayIndex);
            if (section == null) {
                return this.combineWithFallback(
                        level,
                        sections,
                        sectionIndex,
                        placementIndex,
                        maxBlocks - processedBlocks,
                        processedBlocks,
                        changedBlocks,
                        skippedBlocks,
                        directSections,
                        chunk,
                        sectionUpdates,
                        lightBatch,
                        heightmapPlan
                );
            }

            int endIndex = Math.min(sectionBatch.placementCount(), placementIndex + (maxBlocks - processedBlocks));
            SectionUpdate update = sectionUpdates.computeIfAbsent(
                    sectionBatch.sectionY(),
                    ignored -> new SectionUpdate(SectionPos.of(chunk.getPos(), sectionBatch.sectionY()), section)
            );
            int sectionProcessed = 0;
            for (int index = placementIndex; index < endIndex; index++) {
                CellResult cellResult = this.applyCell(
                        level,
                        section,
                        sectionBatch.sectionY(),
                        sectionBatch.placements().get(index),
                        mutablePos,
                        update,
                        lightBatch,
                        heightmapPlan
                );
                changedBlocks += cellResult.changed() ? 1 : 0;
                skippedBlocks += cellResult.skipped() ? 1 : 0;
                processedBlocks += 1;
                sectionProcessed += 1;
            }

            if (sectionProcessed > 0) {
                directSections += 1;
            }
            if (placementIndex + sectionProcessed >= sectionBatch.placementCount()) {
                sectionIndex += 1;
                placementIndex = 0;
            } else {
                placementIndex += sectionProcessed;
            }
        }

        BlockCommitResult directResult = this.finishDirect(
                level,
                chunk,
                processedBlocks,
                changedBlocks,
                skippedBlocks,
                directSections,
                sectionUpdates,
                lightBatch,
                heightmapPlan
        );
        return new DirectChunkApplyResult(processedBlocks, sectionIndex, placementIndex, directResult);
    }

    private LevelChunk loadedChunk(ServerLevel level, ChunkBatch batch) {
        if (level == null || batch == null || batch.chunk() == null) {
            return null;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(batch.chunk().x(), batch.chunk().z());
        if (chunk != null || !WorldApplyChunkLoadContext.allowsSynchronousLoad()) {
            return chunk;
        }
        return level.getChunk(batch.chunk().x(), batch.chunk().z());
    }

    private DirectChunkApplyResult combineWithFallback(
            ServerLevel level,
            List<SectionBatch> sections,
            int sectionIndex,
            int placementIndex,
            int maxBlocks,
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            int directSections,
            LevelChunk chunk,
            Map<Integer, SectionUpdate> sectionUpdates,
            SectionLightUpdateBatch lightBatch,
            ChunkHeightmapUpdatePlan heightmapPlan
    ) {
        BlockCommitResult directResult = this.finishDirect(
                level,
                chunk,
                processedBlocks,
                changedBlocks,
                skippedBlocks,
                directSections,
                sectionUpdates,
                lightBatch,
                heightmapPlan
        );
        DirectChunkApplyResult fallback = this.applyFallbackSection(level, sections, sectionIndex, placementIndex, maxBlocks);
        return new DirectChunkApplyResult(
                processedBlocks + fallback.processedBlocks(),
                fallback.nextSectionIndex(),
                fallback.nextPlacementIndex(),
                BlockCommitResult.combine(directResult, fallback.commitResult())
        );
    }

    private DirectChunkApplyResult applyFallbackSection(
            ServerLevel level,
            List<SectionBatch> sections,
            int sectionIndex,
            int placementIndex,
            int maxBlocks
    ) {
        if (sectionIndex < 0 || sectionIndex >= sections.size()) {
            return DirectChunkApplyResult.none(sectionIndex, placementIndex);
        }
        SectionBatch section = sections.get(sectionIndex);
        BlockCommitResult result = this.sectionFallback.apply(level, section, placementIndex, maxBlocks);
        int processed = result.processedBlocks();
        int nextPlacementIndex = placementIndex + processed;
        int nextSectionIndex = sectionIndex;
        if (nextPlacementIndex >= section.placementCount()) {
            nextSectionIndex += 1;
            nextPlacementIndex = 0;
        }
        return new DirectChunkApplyResult(processed, nextSectionIndex, nextPlacementIndex, result);
    }

    private CellResult applyCell(
            ServerLevel level,
            LevelChunkSection section,
            int sectionY,
            PreparedBlockPlacement placement,
            BlockPos.MutableBlockPos mutablePos,
            SectionUpdate update,
            SectionLightUpdateBatch lightBatch,
            ChunkHeightmapUpdatePlan heightmapPlan
    ) {
        PersistentBlockStatePolicy.PersistentBlockState persistentState =
                this.blockStatePolicy.normalize(placement.state(), placement.blockEntityTag());
        BlockPos pos = placement.pos();
        BlockState targetState = persistentState.state();
        CompoundTag targetBlockEntityTag = persistentState.blockEntityTag();
        int localX = pos.getX() & 15;
        int localY = pos.getY() & 15;
        int localZ = pos.getZ() & 15;
        BlockState currentState = section.getBlockState(localX, localY, localZ);
        mutablePos.set(pos.getX(), pos.getY(), pos.getZ());
        if (!this.updateDecider.requiresUpdate(level, mutablePos, currentState, targetState, targetBlockEntityTag)) {
            return CellResult.skippedResult();
        }

        if (!this.deleteFastPathClassifier.canDelete(currentState, targetState, targetBlockEntityTag)) {
            level.removeBlockEntity(mutablePos);
            level.updatePOIOnBlockStateChange(mutablePos, currentState, targetState);
        }
        section.setBlockState(localX, localY, localZ, targetState, false);
        heightmapPlan.record(sectionY, SectionChangeMask.localIndex(localX, localY, localZ));
        this.lightUpdatePlanner.plan(lightBatch, mutablePos, currentState, targetState);
        update.changedCells().add(SectionPos.sectionRelativePos(mutablePos));
        return CellResult.changedResult();
    }

    private BlockCommitResult finishDirect(
            ServerLevel level,
            LevelChunk chunk,
            int processedBlocks,
            int changedBlocks,
            int skippedBlocks,
            int directSections,
            Map<Integer, SectionUpdate> sectionUpdates,
            SectionLightUpdateBatch lightBatch,
            ChunkHeightmapUpdatePlan heightmapPlan
    ) {
        if (chunk != null && changedBlocks > 0) {
            heightmapPlan.apply(chunk);
            chunk.markUnsaved();
        }
        int lightChecks = this.lightUpdatePlanner.apply(level, lightBatch);
        int sectionPackets = 0;
        if (level != null) {
            for (SectionUpdate update : sectionUpdates.values()) {
                sectionPackets += this.updateBroadcaster.broadcastSection(
                        level,
                        update.sectionPos(),
                        update.changedCells(),
                        update.section()
                );
            }
        }
        return BlockCommitResult.direct(
                processedBlocks,
                changedBlocks,
                skippedBlocks,
                sectionPackets,
                lightChecks,
                directSections
        );
    }

    private record SectionUpdate(SectionPos sectionPos, LevelChunkSection section, ShortSet changedCells) {

        private SectionUpdate(SectionPos sectionPos, LevelChunkSection section) {
            this(sectionPos, section, new ShortOpenHashSet());
        }
    }

    private record CellResult(boolean changed, boolean skipped) {

        private static CellResult changedResult() {
            return new CellResult(true, false);
        }

        private static CellResult skippedResult() {
            return new CellResult(false, true);
        }
    }
}
