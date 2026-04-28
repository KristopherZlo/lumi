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
            batches.add(new PreparedChunkBatch(
                    chunk,
                    List.copyOf(grouped.getOrDefault(chunk, List.of())),
                    this.toEntityBatch(groupedEntities.getOrDefault(chunk, List.of()), applyNewValues)
            ));
        }
        return batches;
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
}
