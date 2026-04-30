package io.github.luma.minecraft.world;

import io.github.luma.domain.model.ChunkPoint;
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
}
