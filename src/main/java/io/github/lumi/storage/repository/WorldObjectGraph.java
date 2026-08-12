package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads one immutable world tree into its object inventory and coordinate leaves. */
public final class WorldObjectGraph {
    private static final int REGION_SIZE = 32;
    private final WorldObjectRepository objects;

    public WorldObjectGraph(WorldObjectRepository objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public Snapshot scan(ObjectId root) throws IOException {
        Objects.requireNonNull(root, "root");
        try (var reader = objects.beginReadSession()) {
            return scan(root, reader);
        }
    }

    public Snapshot scan(
            ObjectId root,
            WorldObjectRepository.Reader reader) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(reader, "reader");
        Set<ObjectId> reachable = new HashSet<>();
        Map<HistoryKey, ObjectId> leaves = new HashMap<>();
        reachable.add(root);
        for (var regionEntry : reader.readDimension(root).regions().entrySet()) {
            ObjectId regionId = regionEntry.getValue();
            reachable.add(regionId);
            int regionChunkX = Math.multiplyExact(regionEntry.getKey().x(), REGION_SIZE);
            int regionChunkZ = Math.multiplyExact(regionEntry.getKey().z(), REGION_SIZE);
            for (var chunkEntry : reader.readRegion(regionId).chunks().entrySet()) {
                ObjectId chunkId = chunkEntry.getValue();
                reachable.add(chunkId);
                int chunkX = Math.addExact(regionChunkX, chunkEntry.getKey().x());
                int chunkZ = Math.addExact(regionChunkZ, chunkEntry.getKey().z());
                var chunk = reader.readChunk(chunkId);
                chunk.sections().forEach((sectionY, sectionId) -> {
                    reachable.add(sectionId);
                    leaves.put(new SectionKey(chunkX, sectionY, chunkZ), sectionId);
                });
                chunk.entities().ifPresent(entityId -> {
                    reachable.add(entityId);
                    leaves.put(new EntityChunkKey(chunkX, chunkZ), entityId);
                });
            }
        }
        return new Snapshot(reachable, leaves);
    }

    public record Snapshot(Set<ObjectId> reachable, Map<HistoryKey, ObjectId> leaves) {
        public Snapshot {
            reachable = Set.copyOf(Objects.requireNonNull(reachable, "reachable"));
            leaves = Map.copyOf(Objects.requireNonNull(leaves, "leaves"));
        }
    }
}
