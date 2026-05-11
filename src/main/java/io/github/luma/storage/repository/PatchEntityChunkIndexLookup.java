package io.github.luma.storage.repository;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.PatchEntityChunkIndex;
import io.github.luma.domain.model.PatchMetadata;
import io.github.luma.domain.model.StoredEntityChange;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PatchEntityChunkIndexLookup {

    List<PatchEntityChunkIndex> build(List<StoredEntityChange> entityChanges) {
        if (entityChanges == null || entityChanges.isEmpty()) {
            return List.of();
        }
        List<PatchEntityChunkIndex> index = new ArrayList<>(entityChanges.size());
        for (StoredEntityChange change : entityChanges) {
            ChunkPoint frameChunk = change.chunk();
            ChunkPoint oldChunk = change.oldValue() == null ? null : change.oldValue().chunk();
            ChunkPoint newChunk = change.newValue() == null ? null : change.newValue().chunk();
            index.add(new PatchEntityChunkIndex(
                    change.entityId(),
                    frameChunk.x(),
                    frameChunk.z(),
                    oldChunk == null ? null : oldChunk.x(),
                    oldChunk == null ? null : oldChunk.z(),
                    newChunk == null ? null : newChunk.x(),
                    newChunk == null ? null : newChunk.z()
            ));
        }
        return List.copyOf(index);
    }

    Set<ChunkPoint> frameChunksForEntityIds(PatchMetadata metadata, Set<String> entityIds) {
        if (metadata.entityChunkIndex().isEmpty() || entityIds == null || entityIds.isEmpty()) {
            return Set.of();
        }
        Set<ChunkPoint> chunks = new LinkedHashSet<>();
        for (PatchEntityChunkIndex entry : metadata.entityChunkIndex()) {
            if (entry != null && entityIds.contains(entry.entityId())) {
                chunks.add(entry.frameChunk());
            }
        }
        return chunks;
    }

    Set<ChunkPoint> frameChunksForEntityChunks(PatchMetadata metadata, Set<ChunkPoint> chunks) {
        if (metadata.entityChunkIndex().isEmpty() || chunks == null || chunks.isEmpty()) {
            return Set.of();
        }
        Set<ChunkPoint> frameChunks = new LinkedHashSet<>();
        for (PatchEntityChunkIndex entry : metadata.entityChunkIndex()) {
            if (entry == null) {
                continue;
            }
            for (ChunkPoint chunk : chunks) {
                if (entry.touches(chunk)) {
                    frameChunks.add(entry.frameChunk());
                    break;
                }
            }
        }
        return frameChunks;
    }

    boolean touchesAnyChunk(StoredEntityChange change, Set<ChunkPoint> chunks) {
        if (change == null || chunks == null || chunks.isEmpty()) {
            return false;
        }
        if (change.oldValue() != null && chunks.contains(change.oldValue().chunk())) {
            return true;
        }
        if (change.newValue() != null && chunks.contains(change.newValue().chunk())) {
            return true;
        }
        return chunks.contains(change.chunk());
    }
}
