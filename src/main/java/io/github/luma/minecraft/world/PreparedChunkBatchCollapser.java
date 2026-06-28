package io.github.luma.minecraft.world;

import io.github.luma.domain.model.SectionChangeMask;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.EntityPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Collapses prepared chunk batches without flattening native sections first.
 */
public final class PreparedChunkBatchCollapser {

    private final ConnectedBlockPlacementExpander connectedBlockPlacementExpander;
    private final PistonMechanismPlacementExpander pistonMechanismPlacementExpander;
    private final SectionApplySafetyClassifier sectionApplySafetyClassifier;

    public PreparedChunkBatchCollapser() {
        this(new ConnectedBlockPlacementExpander(), new PistonMechanismPlacementExpander(), new SectionApplySafetyClassifier());
    }

    PreparedChunkBatchCollapser(
            ConnectedBlockPlacementExpander connectedBlockPlacementExpander,
            PistonMechanismPlacementExpander pistonMechanismPlacementExpander,
            SectionApplySafetyClassifier sectionApplySafetyClassifier
    ) {
        this.connectedBlockPlacementExpander = connectedBlockPlacementExpander;
        this.pistonMechanismPlacementExpander = pistonMechanismPlacementExpander;
        this.sectionApplySafetyClassifier = sectionApplySafetyClassifier;
    }

    public List<PreparedChunkBatch> collapse(List<PreparedChunkBatch> batches) {
        Map<SectionKey, SectionAccumulator> sections = new LinkedHashMap<>();
        EntityOperationAccumulator entities = new EntityOperationAccumulator();
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
                entities.add(batch.chunk(), batch.entityBatch());
            }
        }

        this.expandConnectedPlacements(sections);
        this.expandPistonPlacements(sections);
        return this.toBatches(sections, entities.toBatchesByChunk());
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
                nativeSection.buffer().replayHintAt(localIndex),
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
                placement.replayHint(),
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
                if (section != null && section.contains(placement.pos())
                        && !section.shouldReplaceTransient(placement)) {
                    continue;
                }
                this.addPlacement(sections, placement, false);
            }
        }
    }

    private void expandPistonPlacements(Map<SectionKey, SectionAccumulator> sections) {
        List<PreparedBlockPlacement> candidates = new ArrayList<>();
        for (SectionAccumulator section : sections.values()) {
            candidates.addAll(section.placements());
        }
        List<PreparedBlockPlacement> expanded = this.pistonMechanismPlacementExpander.expandTargets(candidates);
        for (PreparedBlockPlacement placement : expanded) {
            SectionAccumulator section = sections.get(SectionKey.from(placement.pos()));
            if (section != null && section.contains(placement.pos())
                    && !section.shouldReplaceTransient(placement)) {
                continue;
            }
            this.addPlacement(sections, placement, false);
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
            List<PreparedBlockPlacement> placements = PistonMechanismPlacementExpander.ordered(
                    ConnectedBlockPlacementExpander.ordered(sparsePlacements.getOrDefault(chunk, List.of()))
            );
            EntityAccumulator entityAccumulator = entities.get(chunk);
            EntityBatch entityBatch = entityAccumulator == null
                    ? EntityBatch.empty()
                    : entityAccumulator.toBatch();
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
        private final PreparedBlockPlacement.ReplayHint[] replayHints =
                new PreparedBlockPlacement.ReplayHint[SectionChangeMask.ENTRY_COUNT];
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

        private void put(
                int localIndex,
                BlockState state,
                CompoundTag blockEntityTag,
                PreparedBlockPlacement.ReplayHint replayHint,
                boolean connectedExpansionCandidate
        ) {
            if (localIndex < 0 || localIndex >= SectionChangeMask.ENTRY_COUNT) {
                return;
            }
            PreparedBlockPlacement.ReplayHint existingHint = this.changedCells[localIndex]
                    ? this.replayHints[localIndex]
                    : PreparedBlockPlacement.ReplayHint.NONE;
            PreparedBlockPlacement.ReplayHint incomingHint = replayHint == null
                    ? PreparedBlockPlacement.ReplayHint.NONE
                    : replayHint;
            this.changedCells[localIndex] = true;
            this.states[localIndex] = state == null ? Blocks.AIR.defaultBlockState() : state;
            this.blockEntityTags[localIndex] = blockEntityTag == null ? null : blockEntityTag.copy();
            this.replayHints[localIndex] = PreparedBlockPlacement.ReplayHint.merge(existingHint, incomingHint);
            this.connectedExpansionCandidates[localIndex] = connectedExpansionCandidate;
        }

        private boolean contains(BlockPos pos) {
            return pos != null && this.changedCells[SectionChangeMask.localIndex(pos.getX(), pos.getY(), pos.getZ())];
        }

        private boolean shouldReplaceTransient(PreparedBlockPlacement placement) {
            if (placement == null || placement.pos() == null || placement.state() == null || placement.state().isAir()) {
                return false;
            }
            int localIndex = SectionChangeMask.localIndex(
                    placement.pos().getX(),
                    placement.pos().getY(),
                    placement.pos().getZ()
            );
            BlockState currentState = this.states[localIndex];
            return currentState != null && (currentState.isAir() || currentState.is(Blocks.MOVING_PISTON));
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

        private List<PreparedBlockPlacement> placements() {
            List<PreparedBlockPlacement> placements = new ArrayList<>();
            for (int localIndex = 0; localIndex < this.changedCells.length; localIndex++) {
                if (this.changedCells[localIndex]) {
                    placements.add(this.toPlacement(localIndex));
                }
            }
            return placements;
        }

        private LumiSectionBuffer toBuffer() {
            LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(this.sectionY);
            for (int localIndex = 0; localIndex < this.changedCells.length; localIndex++) {
                if (!this.changedCells[localIndex]) {
                    continue;
                }
                builder.set(
                        localIndex,
                        this.states[localIndex],
                        this.blockEntityTags[localIndex],
                        this.replayHints[localIndex]
                );
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
                    this.blockEntityTags[localIndex],
                    this.replayHints[localIndex]
            );
        }
    }

    private static final class EntityAccumulator {

        private final List<CompoundTag> spawns = new ArrayList<>();
        private final List<String> removals = new ArrayList<>();
        private final List<CompoundTag> updates = new ArrayList<>();
        private boolean replaceEntities;

        private EntityBatch toBatch() {
            return new EntityBatch(this.spawns, this.removals, this.updates, this.replaceEntities);
        }
    }

    private static final class EntityOperationAccumulator {

        private final Map<String, EntityOperation> operations = new LinkedHashMap<>();
        private int anonymousIndex;

        private void add(ChunkPoint chunk, EntityBatch batch) {
            if (batch.replaceEntities()) {
                this.markReplace(chunk);
            }
            for (String entityId : batch.entityIdsToRemove()) {
                this.addRemoval(chunk, entityId);
            }
            for (CompoundTag tag : batch.entitiesToUpdate()) {
                ChunkPoint targetChunk = batch.replaceEntities() ? chunk : chunkFor(tag, chunk);
                this.addTarget(targetChunk, tag, EntityOperationKind.UPDATE);
            }
            for (CompoundTag tag : batch.entitiesToSpawn()) {
                this.addTarget(chunkFor(tag, chunk), tag, EntityOperationKind.SPAWN);
            }
        }

        private void addRemoval(ChunkPoint chunk, String entityId) {
            if (entityId == null || entityId.isBlank()) {
                return;
            }
            EntityOperation current = this.operations.get(entityId);
            if (current == null) {
                this.operations.put(entityId, EntityOperation.remove(entityId, chunk));
            } else if (current.kind() == EntityOperationKind.SPAWN) {
                this.operations.remove(entityId);
            } else {
                this.operations.put(entityId, EntityOperation.remove(entityId, chunk));
            }
        }

        private void addTarget(ChunkPoint chunk, CompoundTag tag, EntityOperationKind incomingKind) {
            String entityId = this.entityId(tag);
            EntityOperation current = this.operations.get(entityId);
            EntityOperationKind nextKind = incomingKind;
            if (current != null) {
                nextKind = current.kind() == EntityOperationKind.SPAWN
                        ? EntityOperationKind.SPAWN
                        : EntityOperationKind.UPDATE;
            }
            this.operations.put(entityId, EntityOperation.target(entityId, chunk, tag, nextKind));
        }

        private Map<ChunkPoint, EntityAccumulator> toBatchesByChunk() {
            Map<ChunkPoint, EntityAccumulator> chunks = new LinkedHashMap<>();
            for (ChunkPoint chunk : this.replaceChunks) {
                chunks.computeIfAbsent(chunk, ignored -> new EntityAccumulator()).replaceEntities = true;
            }
            for (EntityOperation operation : this.operations.values()) {
                EntityAccumulator accumulator = chunks.computeIfAbsent(operation.chunk(), ignored -> new EntityAccumulator());
                switch (operation.kind()) {
                    case SPAWN -> accumulator.spawns.add(operation.tag().copy());
                    case REMOVE -> accumulator.removals.add(operation.entityId());
                    case UPDATE -> accumulator.updates.add(operation.tag().copy());
                }
            }
            return chunks;
        }

        private final LinkedHashSet<ChunkPoint> replaceChunks = new LinkedHashSet<>();

        private void markReplace(ChunkPoint chunk) {
            if (chunk != null) {
                this.replaceChunks.add(chunk);
            }
        }

        private String entityId(CompoundTag tag) {
            Optional<UUID> uuid = EntityPayload.readUuid(tag);
            if (uuid.isPresent()) {
                return uuid.get().toString();
            }
            return "__anonymous_entity_" + this.anonymousIndex++;
        }

        private static ChunkPoint chunkFor(CompoundTag tag, ChunkPoint fallback) {
            if (tag == null || tag.isEmpty()) {
                return fallback;
            }
            ChunkPoint chunk = new EntityPayload(tag).chunk();
            return chunk == null ? fallback : chunk;
        }
    }

    private record EntityOperation(
            String entityId,
            ChunkPoint chunk,
            CompoundTag tag,
            EntityOperationKind kind
    ) {

        private static EntityOperation remove(String entityId, ChunkPoint chunk) {
            return new EntityOperation(entityId, chunk, new CompoundTag(), EntityOperationKind.REMOVE);
        }

        private static EntityOperation target(
                String entityId,
                ChunkPoint chunk,
                CompoundTag tag,
                EntityOperationKind kind
        ) {
            return new EntityOperation(entityId, chunk, tag == null ? new CompoundTag() : tag.copy(), kind);
        }
    }

    private enum EntityOperationKind {
        SPAWN,
        REMOVE,
        UPDATE
    }
}
