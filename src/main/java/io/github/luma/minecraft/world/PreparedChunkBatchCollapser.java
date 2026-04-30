package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Collapses prepared chunk batches without flattening native sections first.
 */
public final class PreparedChunkBatchCollapser {

    private final ConnectedBlockPlacementExpander connectedBlockPlacementExpander;
    private final SectionApplySafetyClassifier sectionApplySafetyClassifier;

    public PreparedChunkBatchCollapser() {
        this(new ConnectedBlockPlacementExpander(), new SectionApplySafetyClassifier());
    }

    PreparedChunkBatchCollapser(
            ConnectedBlockPlacementExpander connectedBlockPlacementExpander,
            SectionApplySafetyClassifier sectionApplySafetyClassifier
    ) {
        this.connectedBlockPlacementExpander = connectedBlockPlacementExpander;
        this.sectionApplySafetyClassifier = sectionApplySafetyClassifier;
    }

    public List<PreparedChunkBatch> collapse(List<PreparedChunkBatch> batches) {
        Map<SectionKey, SectionAccumulator> sections = new LinkedHashMap<>();
        Map<ChunkPoint, EntityAccumulator> entities = new LinkedHashMap<>();
        for (PreparedChunkBatch batch : batches == null ? List.<PreparedChunkBatch>of() : batches) {
            if (batch == null) {
                continue;
            }
            for (PreparedSectionApplyBatch nativeSection : batch.nativeSections()) {
                this.addNativeSection(sections, nativeSection);
            }
            for (PreparedBlockPlacement placement : batch.placements()) {
                this.addPlacement(sections, placement, true);
            }
            if (!batch.entityBatch().isEmpty()) {
                entities.computeIfAbsent(batch.chunk(), ignored -> new EntityAccumulator())
                        .add(batch.entityBatch());
            }
        }

        this.expandConnectedPlacements(sections);
        return this.toBatches(sections, entities);
    }

    private void addNativeSection(
            Map<SectionKey, SectionAccumulator> sections,
            PreparedSectionApplyBatch nativeSection
    ) {
        if (nativeSection == null) {
            return;
        }
        SectionAccumulator section = sections.computeIfAbsent(
                new SectionKey(nativeSection.chunk(), nativeSection.sectionY()),
                key -> new SectionAccumulator(key.chunk(), key.sectionY())
        );
        nativeSection.buffer().changedCells().forEachSetCell(localIndex -> section.put(
                localIndex,
                nativeSection.buffer().targetStateAt(localIndex),
                nativeSection.buffer().blockEntityPlan().tagAt(localIndex),
                !nativeSection.fullSection()
        ));
    }

    private void addPlacement(
            Map<SectionKey, SectionAccumulator> sections,
            PreparedBlockPlacement placement,
            boolean expandConnected
    ) {
        if (placement == null || placement.pos() == null) {
            return;
        }
        ChunkPoint chunk = ChunkPoint.from(placement.pos());
        int sectionY = Math.floorDiv(placement.pos().getY(), 16);
        SectionAccumulator section = sections.computeIfAbsent(
                new SectionKey(chunk, sectionY),
                key -> new SectionAccumulator(key.chunk(), key.sectionY())
        );
        section.put(
                SectionChangeMask.localIndex(placement.pos().getX(), placement.pos().getY(), placement.pos().getZ()),
                placement.state(),
                placement.blockEntityTag(),
                expandConnected
        );
    }

    private void expandConnectedPlacements(Map<SectionKey, SectionAccumulator> sections) {
        List<PreparedBlockPlacement> expanded = new ArrayList<>();
        for (SectionAccumulator section : sections.values()) {
            expanded.addAll(section.connectedExpansionCandidates());
        }
        if (expanded.isEmpty()) {
            return;
        }

        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = this.connectedBlockPlacementExpander.groupByChunk(
                this.connectedBlockPlacementExpander.expandTargets(expanded)
        );
        for (List<PreparedBlockPlacement> placements : grouped.values()) {
            for (PreparedBlockPlacement placement : placements) {
                SectionAccumulator section = sections.get(SectionKey.from(placement.pos()));
                if (section != null && section.contains(placement.pos())) {
                    continue;
                }
                this.addPlacement(sections, placement, false);
            }
        }
    }

    private List<PreparedChunkBatch> toBatches(
            Map<SectionKey, SectionAccumulator> sections,
            Map<ChunkPoint, EntityAccumulator> entities
    ) {
        Map<ChunkPoint, List<PreparedBlockPlacement>> sparsePlacements = new LinkedHashMap<>();
        Map<ChunkPoint, List<PreparedSectionApplyBatch>> nativeSections = new LinkedHashMap<>();
        for (SectionAccumulator section : sections.values()) {
            LumiSectionBuffer buffer = section.toBuffer();
            if (buffer.changedCellCount() <= 0) {
                continue;
            }
            boolean fullSection = buffer.changedCellCount() == SectionChangeMask.ENTRY_COUNT;
            SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, fullSection);
            PreparedSectionApplyBatch sectionBatch = new PreparedSectionApplyBatch(
                    section.chunk(),
                    section.sectionY(),
                    buffer,
                    profile,
                    fullSection
            );
            if (profile.path() == SectionApplyPath.DIRECT_SECTION) {
                sparsePlacements.computeIfAbsent(section.chunk(), ignored -> new ArrayList<>())
                        .addAll(sectionBatch.toPlacements());
            } else {
                nativeSections.computeIfAbsent(section.chunk(), ignored -> new ArrayList<>()).add(sectionBatch);
            }
        }

        List<PreparedChunkBatch> result = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(sparsePlacements.keySet());
        chunks.addAll(nativeSections.keySet());
        chunks.addAll(entities.keySet());
        for (ChunkPoint chunk : chunks) {
            List<PreparedBlockPlacement> placements = ConnectedBlockPlacementExpander.ordered(
                    sparsePlacements.getOrDefault(chunk, List.of())
            );
            EntityBatch entityBatch = entities.getOrDefault(chunk, EntityAccumulator.EMPTY).toBatch();
            List<PreparedSectionApplyBatch> sectionBatches = nativeSections.getOrDefault(chunk, List.of());
            if (!placements.isEmpty() || !sectionBatches.isEmpty() || !entityBatch.isEmpty()) {
                result.add(new PreparedChunkBatch(chunk, placements, sectionBatches, entityBatch));
            }
        }
        return List.copyOf(result);
    }

    private record SectionKey(ChunkPoint chunk, int sectionY) {

        private static SectionKey from(BlockPos pos) {
            return new SectionKey(ChunkPoint.from(pos), Math.floorDiv(pos.getY(), 16));
        }
    }

    private static final class SectionAccumulator {

        private final ChunkPoint chunk;
        private final int sectionY;
        private final boolean[] changedCells = new boolean[SectionChangeMask.ENTRY_COUNT];
        private final BlockState[] states = new BlockState[SectionChangeMask.ENTRY_COUNT];
        private final CompoundTag[] blockEntityTags = new CompoundTag[SectionChangeMask.ENTRY_COUNT];
        private final boolean[] connectedExpansionCandidates = new boolean[SectionChangeMask.ENTRY_COUNT];

        private SectionAccumulator(ChunkPoint chunk, int sectionY) {
            this.chunk = chunk;
            this.sectionY = sectionY;
        }

        private ChunkPoint chunk() {
            return this.chunk;
        }

        private int sectionY() {
            return this.sectionY;
        }

        private void put(int localIndex, BlockState state, CompoundTag blockEntityTag, boolean connectedExpansionCandidate) {
            if (localIndex < 0 || localIndex >= SectionChangeMask.ENTRY_COUNT) {
                return;
            }
            this.changedCells[localIndex] = true;
            this.states[localIndex] = state == null ? Blocks.AIR.defaultBlockState() : state;
            this.blockEntityTags[localIndex] = blockEntityTag == null ? null : blockEntityTag.copy();
            this.connectedExpansionCandidates[localIndex] = connectedExpansionCandidate;
        }

        private boolean contains(BlockPos pos) {
            return pos != null && this.changedCells[SectionChangeMask.localIndex(pos.getX(), pos.getY(), pos.getZ())];
        }

        private List<PreparedBlockPlacement> connectedExpansionCandidates() {
            List<PreparedBlockPlacement> placements = new ArrayList<>();
            for (int localIndex = 0; localIndex < this.connectedExpansionCandidates.length; localIndex++) {
                if (!this.connectedExpansionCandidates[localIndex]) {
                    continue;
                }
                placements.add(this.toPlacement(localIndex));
            }
            return placements;
        }

        private LumiSectionBuffer toBuffer() {
            LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(this.sectionY);
            for (int localIndex = 0; localIndex < this.changedCells.length; localIndex++) {
                if (!this.changedCells[localIndex]) {
                    continue;
                }
                builder.set(localIndex, this.states[localIndex], this.blockEntityTags[localIndex]);
            }
            return builder.build();
        }

        private PreparedBlockPlacement toPlacement(int localIndex) {
            return new PreparedBlockPlacement(
                    new BlockPos(
                            (this.chunk.x() << 4) + SectionChangeMask.localX(localIndex),
                            (this.sectionY << 4) + SectionChangeMask.localY(localIndex),
                            (this.chunk.z() << 4) + SectionChangeMask.localZ(localIndex)
                    ),
                    this.states[localIndex],
                    this.blockEntityTags[localIndex]
            );
        }
    }

    private static final class EntityAccumulator {

        private static final EntityAccumulator EMPTY = new EntityAccumulator();

        private final List<CompoundTag> spawns = new ArrayList<>();
        private final List<String> removals = new ArrayList<>();
        private final List<CompoundTag> updates = new ArrayList<>();

        private void add(EntityBatch batch) {
            this.spawns.addAll(batch.entitiesToSpawn());
            this.removals.addAll(batch.entityIdsToRemove());
            this.updates.addAll(batch.entitiesToUpdate());
        }

        private EntityBatch toBatch() {
            return new EntityBatch(this.spawns, this.removals, this.updates);
        }
    }
}
