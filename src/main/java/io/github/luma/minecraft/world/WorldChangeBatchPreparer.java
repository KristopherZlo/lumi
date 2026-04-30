package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchSectionFrame;
import io.github.luma.domain.model.PatchSectionWorldChanges;
import io.github.luma.domain.model.PatchWorldChanges;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.StoredEntityChange;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Converts persisted domain changes into Minecraft-ready chunk apply batches.
 */
public final class WorldChangeBatchPreparer {

    private final ConnectedBlockPlacementExpander connectedBlockPlacementExpander = new ConnectedBlockPlacementExpander();
    private final SectionApplySafetyClassifier sectionApplySafetyClassifier = new SectionApplySafetyClassifier();

    public List<PreparedChunkBatch> prepareNewValues(ServerLevel level, PatchWorldChanges changes) throws IOException {
        return this.prepare(level, changes.blockChanges(), changes.entityChanges(), true, ProgressListener.NO_OP);
    }

    public List<PreparedChunkBatch> prepareNewValues(
            ServerLevel level,
            PatchSectionWorldChanges changes,
            ProgressListener progressListener
    ) throws IOException {
        return this.prepare(level, changes, true, progressListener);
    }

    public List<PreparedChunkBatch> prepareNewValues(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            ProgressListener progressListener
    ) throws IOException {
        return this.prepare(level, changes, entityChanges, true, progressListener);
    }

    public List<PreparedChunkBatch> prepare(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues
    ) throws IOException {
        return this.prepare(level, changes, entityChanges, applyNewValues, ProgressListener.NO_OP);
    }

    public List<PreparedChunkBatch> prepare(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener
    ) throws IOException {
        changes = changes == null ? List.of() : changes;
        entityChanges = entityChanges == null ? List.of() : entityChanges;
        progressListener = progressListener == null ? ProgressListener.NO_OP : progressListener;
        int total = changes.size() + entityChanges.size();
        int completed = 0;

        List<ConnectedBlockPlacementExpander.ChangePlacement> blockPlacements = new ArrayList<>();
        Map<ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        for (StoredBlockChange change : changes) {
            StatePayload source = applyNewValues ? change.oldValue() : change.newValue();
            StatePayload target = applyNewValues ? change.newValue() : change.oldValue();
            BlockPos pos = new BlockPos(change.pos().x(), change.pos().y(), change.pos().z());
            blockPlacements.add(new ConnectedBlockPlacementExpander.ChangePlacement(
                    new PreparedBlockPlacement(
                            pos,
                            BlockStateNbtCodec.deserializeBlockState(level, target == null ? null : target.stateTag()),
                            target == null || target.blockEntityTag() == null ? null : target.blockEntityTag().copy()
                    ),
                    BlockStateNbtCodec.deserializeBlockState(level, source == null ? null : source.stateTag())
            ));
            completed += 1;
            progressListener.onDecoded(completed, total);
        }
        Map<ChunkPoint, List<PreparedBlockPlacement>> grouped = this.connectedBlockPlacementExpander.groupByChunk(
                this.connectedBlockPlacementExpander.expandChanges(blockPlacements)
        );
        for (StoredEntityChange change : entityChanges) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            completed += 1;
            progressListener.onDecoded(completed, total);
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(grouped.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            batches.add(this.prepareDecodedChunk(
                    chunk,
                    grouped.getOrDefault(chunk, List.of()),
                    this.toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), applyNewValues)
            ));
        }
        return batches;
    }

    public List<PreparedChunkBatch> prepareUndoRedo(
            ServerLevel level,
            List<StoredBlockChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener
    ) throws IOException {
        changes = changes == null ? List.of() : changes;
        if (changes.size() < SectionApplySafetyClassifier.CONTAINER_REWRITE_THRESHOLD) {
            return this.prepare(level, changes, entityChanges, applyNewValues, progressListener);
        }

        List<DecodedStoredChange> decodedChanges = new ArrayList<>(changes.size());
        for (StoredBlockChange change : changes) {
            StatePayload source = applyNewValues ? change.oldValue() : change.newValue();
            StatePayload target = applyNewValues ? change.newValue() : change.oldValue();
            BlockState sourceState = BlockStateNbtCodec.deserializeBlockState(level, source == null ? null : source.stateTag());
            BlockState targetState = BlockStateNbtCodec.deserializeBlockState(level, target == null ? null : target.stateTag());
            if (this.connectedBlockPlacementExpander.requiresCompanion(sourceState)
                    || this.connectedBlockPlacementExpander.requiresCompanion(targetState)) {
                return this.prepare(level, changes, entityChanges, applyNewValues, progressListener);
            }
            decodedChanges.add(new DecodedStoredChange(
                    new BlockPos(change.pos().x(), change.pos().y(), change.pos().z()),
                    targetState,
                    target == null || target.blockEntityTag() == null ? null : target.blockEntityTag().copy()
            ));
        }
        return this.prepareDecodedSectionFirst(
                decodedChanges,
                entityChanges,
                applyNewValues,
                progressListener == null ? ProgressListener.NO_OP : progressListener
        );
    }

    public List<PreparedChunkBatch> prepare(
            ServerLevel level,
            PatchSectionWorldChanges changes,
            boolean applyNewValues,
            ProgressListener progressListener
    ) throws IOException {
        if (changes == null || changes.sectionFrames().isEmpty()) {
            return this.prepare(level, List.of(), changes == null ? List.of() : changes.entityChanges(), applyNewValues, progressListener);
        }
        progressListener = progressListener == null ? ProgressListener.NO_OP : progressListener;
        int total = changes.sectionFrames().stream()
                .mapToInt(frame -> new SectionChangeMask(frame.changedMask()).cardinality())
                .sum() + changes.entityChanges().size();
        int[] completed = new int[] {0};

        Map<ChunkPoint, List<PreparedSectionApplyBatch>> nativeSections = new LinkedHashMap<>();
        Map<ChunkPoint, List<PreparedBlockPlacement>> sparsePlacements = new LinkedHashMap<>();
        for (PatchSectionFrame frame : changes.sectionFrames()) {
            ChunkPoint chunk = new ChunkPoint(frame.chunkX(), frame.chunkZ());
            LumiSectionBuffer buffer = this.toSectionBuffer(level, frame, applyNewValues, completed, total, progressListener);
            boolean fullSection = buffer.changedCellCount() == SectionChangeMask.ENTRY_COUNT;
            SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, fullSection);
            PreparedSectionApplyBatch nativeBatch = new PreparedSectionApplyBatch(
                    chunk,
                    frame.sectionY(),
                    buffer,
                    profile,
                    fullSection
            );
            if (profile.path() == SectionApplyPath.SECTION_NATIVE) {
                nativeSections.computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(nativeBatch);
            } else {
                sparsePlacements.computeIfAbsent(chunk, ignored -> new ArrayList<>()).addAll(nativeBatch.toPlacements());
            }
        }

        Map<ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        for (StoredEntityChange change : changes.entityChanges()) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            completed[0] += 1;
            progressListener.onDecoded(completed[0], total);
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(nativeSections.keySet());
        chunks.addAll(sparsePlacements.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            SectionSplit split = this.splitSections(chunk, sparsePlacements.getOrDefault(chunk, List.of()));
            List<PreparedSectionApplyBatch> combinedNativeSections = new ArrayList<>(nativeSections.getOrDefault(chunk, List.of()));
            combinedNativeSections.addAll(split.nativeSections());
            batches.add(new PreparedChunkBatch(
                    chunk,
                    split.sparsePlacements(),
                    combinedNativeSections,
                    this.toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), applyNewValues)
            ));
        }
        return batches;
    }

    private LumiSectionBuffer toSectionBuffer(
            ServerLevel level,
            PatchSectionFrame frame,
            boolean applyNewValues,
            int[] completed,
            int total,
            ProgressListener progressListener
    ) throws IOException {
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(frame.sectionY());
        List<Integer> localIndexes = new ArrayList<>();
        new SectionChangeMask(frame.changedMask()).forEachSetCell(localIndexes::add);
        int[] stateIds = applyNewValues ? frame.newStateIds() : frame.oldStateIds();
        int[] blockEntityIds = applyNewValues ? frame.newBlockEntityIds() : frame.oldBlockEntityIds();
        List<CompoundTag> statePalette = applyNewValues ? frame.newStatePalette() : frame.oldStatePalette();
        List<CompoundTag> blockEntityPalette = applyNewValues ? frame.newBlockEntityPalette() : frame.oldBlockEntityPalette();
        for (int index = 0; index < localIndexes.size(); index++) {
            int localIndex = localIndexes.get(index);
            builder.set(
                    localIndex,
                    BlockStateNbtCodec.deserializeBlockState(level, statePalette.get(stateIds[index])),
                    this.blockEntityAt(blockEntityPalette, blockEntityIds[index])
            );
            completed[0] += 1;
            progressListener.onDecoded(completed[0], total);
        }
        return builder.build();
    }

    private CompoundTag blockEntityAt(List<CompoundTag> palette, int id) {
        return id < 0 ? null : palette.get(id).copy();
    }

    public PreparedChunkBatch prepareDecodedChunk(
            ChunkPoint chunk,
            List<PreparedBlockPlacement> placements,
            EntityBatch entityBatch
    ) {
        SectionSplit split = this.splitSections(chunk, placements);
        return new PreparedChunkBatch(
                chunk,
                split.sparsePlacements(),
                split.nativeSections(),
                entityBatch
        );
    }

    private SectionSplit splitSections(ChunkPoint chunk, List<PreparedBlockPlacement> placements) {
        if (placements == null || placements.isEmpty()) {
            return new SectionSplit(List.of(), List.of());
        }

        Map<Integer, List<PreparedBlockPlacement>> bySection = new LinkedHashMap<>();
        for (PreparedBlockPlacement placement : placements) {
            bySection.computeIfAbsent(Math.floorDiv(placement.pos().getY(), 16), ignored -> new ArrayList<>())
                    .add(placement);
        }

        List<PreparedBlockPlacement> sparse = new ArrayList<>();
        List<PreparedSectionApplyBatch> nativeSections = new ArrayList<>();
        bySection.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    if (entry.getValue().stream().anyMatch(placement -> placement.state() == null)) {
                        sparse.addAll(entry.getValue());
                        return;
                    }
                    LumiSectionBuffer buffer = this.toSectionBuffer(entry.getKey(), entry.getValue());
                    SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, false);
                    if (profile.path() == SectionApplyPath.SECTION_NATIVE) {
                        nativeSections.add(new PreparedSectionApplyBatch(
                                chunk,
                                entry.getKey(),
                                buffer,
                                profile,
                                false
                        ));
                    } else {
                        sparse.addAll(entry.getValue());
                    }
                });
        return new SectionSplit(List.copyOf(sparse), List.copyOf(nativeSections));
    }

    private List<PreparedChunkBatch> prepareDecodedSectionFirst(
            List<DecodedStoredChange> changes,
            List<StoredEntityChange> entityChanges,
            boolean applyNewValues,
            ProgressListener progressListener
    ) {
        entityChanges = entityChanges == null ? List.of() : entityChanges;
        int total = changes.size() + entityChanges.size();
        int completed = 0;
        Map<SectionKey, LumiSectionBuffer.Builder> sectionBuilders = new LinkedHashMap<>();
        for (DecodedStoredChange change : changes) {
            SectionKey key = SectionKey.from(change.pos());
            sectionBuilders.computeIfAbsent(key, ignored -> LumiSectionBuffer.builder(key.sectionY()))
                    .set(
                            change.pos().getX() & 15,
                            change.pos().getY() & 15,
                            change.pos().getZ() & 15,
                            change.targetState(),
                            change.blockEntityTag()
                    );
            completed += 1;
            progressListener.onDecoded(completed, total);
        }

        Map<ChunkPoint, List<PreparedSectionApplyBatch>> nativeSections = new LinkedHashMap<>();
        Map<ChunkPoint, List<PreparedBlockPlacement>> sparsePlacements = new LinkedHashMap<>();
        for (Map.Entry<SectionKey, LumiSectionBuffer.Builder> entry : sectionBuilders.entrySet()) {
            SectionKey key = entry.getKey();
            LumiSectionBuffer buffer = entry.getValue().build();
            boolean fullSection = buffer.changedCellCount() == SectionChangeMask.ENTRY_COUNT;
            SectionApplySafetyProfile profile = this.sectionApplySafetyClassifier.classify(buffer, fullSection);
            PreparedSectionApplyBatch nativeBatch = new PreparedSectionApplyBatch(
                    key.chunk(),
                    key.sectionY(),
                    buffer,
                    profile,
                    fullSection
            );
            if (profile.path() == SectionApplyPath.DIRECT_SECTION) {
                sparsePlacements.computeIfAbsent(key.chunk(), ignored -> new ArrayList<>()).addAll(nativeBatch.toPlacements());
            } else {
                nativeSections.computeIfAbsent(key.chunk(), ignored -> new ArrayList<>()).add(nativeBatch);
            }
        }

        Map<ChunkPoint, List<StoredEntityChange>> groupedEntities = new LinkedHashMap<>();
        for (StoredEntityChange change : entityChanges) {
            groupedEntities.computeIfAbsent(change.chunk(), ignored -> new ArrayList<>()).add(change);
            completed += 1;
            progressListener.onDecoded(completed, total);
        }

        List<PreparedChunkBatch> batches = new ArrayList<>();
        LinkedHashSet<ChunkPoint> chunks = new LinkedHashSet<>();
        chunks.addAll(nativeSections.keySet());
        chunks.addAll(sparsePlacements.keySet());
        chunks.addAll(groupedEntities.keySet());
        for (ChunkPoint chunk : chunks) {
            batches.add(new PreparedChunkBatch(
                    chunk,
                    sparsePlacements.getOrDefault(chunk, List.of()),
                    nativeSections.getOrDefault(chunk, List.of()),
                    this.toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), applyNewValues)
            ));
        }
        return batches;
    }

    private LumiSectionBuffer toSectionBuffer(int sectionY, List<PreparedBlockPlacement> placements) {
        LumiSectionBuffer.Builder builder = LumiSectionBuffer.builder(sectionY);
        for (PreparedBlockPlacement placement : placements) {
            BlockPos pos = placement.pos();
            builder.set(
                    pos.getX() & 15,
                    pos.getY() & 15,
                    pos.getZ() & 15,
                    placement.state(),
                    placement.blockEntityTag()
            );
        }
        return builder.build();
    }

    EntityBatch toEntityBatch(List<StoredEntityChange> changes, boolean applyNewValues) {
        List<CompoundTag> spawns = new ArrayList<>();
        List<String> removals = new ArrayList<>();
        List<CompoundTag> updates = new ArrayList<>();
        for (StoredEntityChange change : changes == null ? List.<StoredEntityChange>of() : changes) {
            StoredEntityChange target = applyNewValues ? change : change.inverse();
            if (target.isSpawn()) {
                spawns.add(target.newValue().copyTag());
            } else if (target.isRemove()) {
                removals.add(target.entityId());
            } else if (target.isUpdate()) {
                updates.add(target.newValue().copyTag());
            }
        }
        return new EntityBatch(spawns, removals, updates);
    }

    @FunctionalInterface
    public interface ProgressListener {

        ProgressListener NO_OP = (completed, total) -> {
        };

        void onDecoded(int completed, int total);
    }

    private record SectionSplit(
            List<PreparedBlockPlacement> sparsePlacements,
            List<PreparedSectionApplyBatch> nativeSections
    ) {
    }

    private record DecodedStoredChange(BlockPos pos, BlockState targetState, CompoundTag blockEntityTag) {
    }

    private record SectionKey(ChunkPoint chunk, int sectionY) {

        private static SectionKey from(BlockPos pos) {
            return new SectionKey(ChunkPoint.from(pos), Math.floorDiv(pos.getY(), 16));
        }
    }
}
