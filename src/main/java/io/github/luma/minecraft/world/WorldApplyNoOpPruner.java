package io.github.luma.minecraft.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Removes already-matching block placements from a loaded chunk batch before
 * tick-time apply work starts.
 */
final class WorldApplyNoOpPruner {

    private final PersistentBlockStatePolicy blockStatePolicy = new PersistentBlockStatePolicy();
    private final BlockPlacementUpdateDecider updateDecider = new BlockPlacementUpdateDecider();
    private final ExactReplayTargetPolicy exactReplayTargetPolicy = new ExactReplayTargetPolicy();
    private final SectionApplySafetyClassifier sectionApplySafetyClassifier = new SectionApplySafetyClassifier();

    ChunkBatch prune(ServerLevel level, ChunkBatch batch) {
        if (level == null || batch == null || batch.chunk() == null || batch.totalPlacements() <= 0) {
            return batch;
        }

        LevelChunk chunk = level.getChunkSource().getChunkNow(batch.chunk().x(), batch.chunk().z());
        if (chunk == null) {
            return batch;
        }

        Map<Integer, PreparedSectionApplyBatch> nativeSections = new LinkedHashMap<>();
        Map<Integer, List<PreparedBlockPlacement>> sparsePlacements = new LinkedHashMap<>();
        LongSet updatedPositions = this.updatedPositions(level, chunk, batch);
        for (PreparedSectionApplyBatch section : batch.orderedNativeSections()) {
            this.pruneNativeSection(level, chunk, section, updatedPositions, nativeSections, sparsePlacements);
        }
        for (SectionBatch section : batch.orderedSections()) {
            this.pruneSparseSection(level, chunk, section, updatedPositions, sparsePlacements);
        }

        Map<Integer, SectionBatch> sparseSections = this.toSparseSections(sparsePlacements);
        Map<BlockPos, CompoundTag> blockEntities = new LinkedHashMap<>();
        for (SectionBatch section : sparseSections.values()) {
            for (PreparedBlockPlacement placement : section.placements()) {
                if (placement.blockEntityTag() != null) {
                    blockEntities.put(placement.pos().immutable(), placement.blockEntityTag().copy());
                }
            }
        }

        return new ChunkBatch(
                batch.chunk(),
                Map.copyOf(nativeSections),
                Map.copyOf(sparseSections),
                Map.copyOf(blockEntities),
                batch.entityBatch(),
                BatchState.COMPLETE
        );
    }

    private void pruneNativeSection(
            ServerLevel level,
            LevelChunk chunk,
            PreparedSectionApplyBatch section,
            LongSet updatedPositions,
            Map<Integer, PreparedSectionApplyBatch> nativeSections,
            Map<Integer, List<PreparedBlockPlacement>> sparsePlacements
    ) {
        if (section == null || section.buffer() == null) {
            return;
        }
        LevelChunkSection liveSection = this.liveSection(chunk, section.sectionY());
        if (liveSection == null) {
            nativeSections.put(section.sectionY(), section);
            return;
        }
        if (section.buffer().isFullUniformAirSection() && liveSection.hasOnlyAir()) {
            return;
        }

        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(section.sectionY());
        int[] keptCells = {0};
        section.buffer().changedCells().forEachSetCell(localIndex -> {
            PreparedBlockPlacement placement = this.placement(section, localIndex);
            if (!this.shouldKeep(level, liveSection, placement, updatedPositions)) {
                return;
            }
            builder.set(
                    localIndex,
                    placement.state(),
                    placement.blockEntityTag(),
                    placement.replayHint()
            );
            keptCells[0] += 1;
        });
        if (keptCells[0] <= 0) {
            return;
        }
        if (keptCells[0] == section.changedCellCount()) {
            nativeSections.put(section.sectionY(), section);
            return;
        }

        LumiSectionBuffer buffer = builder.build();
        SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, false);
        PreparedSectionApplyBatch pruned = new PreparedSectionApplyBatch(
                section.chunk(),
                section.sectionY(),
                buffer,
                profile,
                false
        );
        if (profile.path() == SectionApplyPath.DIRECT_SECTION) {
            sparsePlacements.computeIfAbsent(section.sectionY(), ignored -> new ArrayList<>())
                    .addAll(pruned.toPlacements());
        } else {
            nativeSections.put(section.sectionY(), pruned);
        }
    }

    private void pruneSparseSection(
            ServerLevel level,
            LevelChunk chunk,
            SectionBatch section,
            LongSet updatedPositions,
            Map<Integer, List<PreparedBlockPlacement>> sparsePlacements
    ) {
        if (section == null || section.placementCount() <= 0) {
            return;
        }
        LevelChunkSection liveSection = this.liveSection(chunk, section.sectionY());
        if (liveSection == null) {
            sparsePlacements.computeIfAbsent(section.sectionY(), ignored -> new ArrayList<>())
                    .addAll(section.placements());
            return;
        }
        for (PreparedBlockPlacement placement : section.placements()) {
            if (this.shouldKeep(level, liveSection, placement, updatedPositions)) {
                sparsePlacements.computeIfAbsent(section.sectionY(), ignored -> new ArrayList<>())
                        .add(placement);
            }
        }
    }

    private Map<Integer, SectionBatch> toSparseSections(Map<Integer, List<PreparedBlockPlacement>> sparsePlacements) {
        Map<Integer, SectionBatch> sections = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<PreparedBlockPlacement>> entry : sparsePlacements.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            BitSet changedCells = new BitSet(SectionChangeMask.ENTRY_COUNT);
            for (PreparedBlockPlacement placement : entry.getValue()) {
                changedCells.set(SectionChangeMask.localIndex(
                        placement.pos().getX(),
                        placement.pos().getY(),
                        placement.pos().getZ()
                ));
            }
            sections.put(entry.getKey(), new SectionBatch(
                    entry.getKey(),
                    changedCells,
                    List.copyOf(entry.getValue())
            ));
        }
        return sections;
    }

    private LevelChunkSection liveSection(LevelChunk chunk, int sectionY) {
        int sectionIndex = chunk.getSectionIndexFromSectionY(sectionY);
        if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
            return null;
        }
        return chunk.getSection(sectionIndex);
    }

    private LongSet updatedPositions(ServerLevel level, LevelChunk chunk, ChunkBatch batch) {
        LongOpenHashSet updatedPositions = new LongOpenHashSet();
        for (PreparedSectionApplyBatch section : batch.orderedNativeSections()) {
            if (section == null || section.buffer() == null) {
                continue;
            }
            LevelChunkSection liveSection = this.liveSection(chunk, section.sectionY());
            if (liveSection == null) {
                continue;
            }
            section.buffer().changedCells().forEachSetCell(localIndex -> {
                PreparedBlockPlacement placement = this.placement(section, localIndex);
                if (this.requiresLiveUpdate(level, liveSection, placement)) {
                    updatedPositions.add(placement.pos().asLong());
                }
            });
        }
        for (SectionBatch section : batch.orderedSections()) {
            if (section == null || section.placementCount() <= 0) {
                continue;
            }
            LevelChunkSection liveSection = this.liveSection(chunk, section.sectionY());
            if (liveSection == null) {
                continue;
            }
            for (PreparedBlockPlacement placement : section.placements()) {
                if (this.requiresLiveUpdate(level, liveSection, placement)) {
                    updatedPositions.add(placement.pos().asLong());
                }
            }
        }
        return updatedPositions;
    }

    private boolean shouldKeep(
            ServerLevel level,
            LevelChunkSection section,
            PreparedBlockPlacement placement,
            LongSet updatedPositions
    ) {
        if (placement == null || placement.pos() == null || placement.state() == null) {
            return false;
        }
        if (this.requiresLiveUpdate(level, section, placement)) {
            return true;
        }
        return this.shouldKeepNoOpReplay(placement, updatedPositions);
    }

    boolean shouldKeepNoOpReplay(PreparedBlockPlacement placement, LongSet updatedPositions) {
        if (placement == null || placement.pos() == null || placement.state() == null) {
            return false;
        }
        if (placement.replayHint().forcesFinalReplay()) {
            return true;
        }
        if (placement.replayHint().suppressesPostReplayFluid()) {
            return true;
        }
        if (placement.replayHint().suppressesPostReplayMechanism()) {
            return true;
        }
        if (!this.exactReplayTargetPolicy.requiresFinalReplay(placement)
                && !this.exactReplayTargetPolicy.requiresPostReplayGuard(placement)) {
            return false;
        }
        return this.touchesUpdatedNeighbor(placement.pos(), updatedPositions)
                || this.isChunkBoundary(placement.pos());
    }

    private boolean requiresLiveUpdate(ServerLevel level, LevelChunkSection section, PreparedBlockPlacement placement) {
        if (placement == null || placement.pos() == null || placement.state() == null || section == null) {
            return false;
        }
        PersistentBlockStatePolicy.PersistentBlockState target = this.blockStatePolicy.normalize(
                placement.state(),
                placement.blockEntityTag()
        );
        BlockPos pos = placement.pos();
        BlockState currentState = section.getBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        return this.updateDecider.requiresUpdate(
                level,
                pos,
                currentState,
                target.state(),
                target.blockEntityTag()
        );
    }

    private boolean touchesUpdatedNeighbor(BlockPos pos, LongSet updatedPositions) {
        if (pos == null || updatedPositions == null || updatedPositions.isEmpty()) {
            return false;
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        return updatedPositions.contains(BlockPos.asLong(x + 1, y, z))
                || updatedPositions.contains(BlockPos.asLong(x - 1, y, z))
                || updatedPositions.contains(BlockPos.asLong(x, y + 1, z))
                || updatedPositions.contains(BlockPos.asLong(x, y - 1, z))
                || updatedPositions.contains(BlockPos.asLong(x, y, z + 1))
                || updatedPositions.contains(BlockPos.asLong(x, y, z - 1));
    }

    private boolean isChunkBoundary(BlockPos pos) {
        return pos != null && ((pos.getX() & 15) == 0
                || (pos.getX() & 15) == 15
                || (pos.getZ() & 15) == 0
                || (pos.getZ() & 15) == 15);
    }

    private PreparedBlockPlacement placement(PreparedSectionApplyBatch section, int localIndex) {
        return new PreparedBlockPlacement(
                new BlockPos(
                        (section.chunk().x() << 4) + SectionChangeMask.localX(localIndex),
                        (section.sectionY() << 4) + SectionChangeMask.localY(localIndex),
                        (section.chunk().z() << 4) + SectionChangeMask.localZ(localIndex)
                ),
                section.buffer().targetStateAt(localIndex),
                section.buffer().blockEntityPlan().tagAt(localIndex),
                section.buffer().replayHintAt(localIndex)
        );
    }
}
