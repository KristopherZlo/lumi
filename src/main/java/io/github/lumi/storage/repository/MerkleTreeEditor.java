package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class MerkleTreeEditor {
    private static final int REGION_SIZE = 32;
    private final WorldObjectRepository objects;

    public MerkleTreeEditor(WorldObjectRepository objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    public ObjectId update(Optional<ObjectId> baseRoot, Map<HistoryKey, ObjectId> changes) throws IOException {
        Objects.requireNonNull(baseRoot, "baseRoot");
        Objects.requireNonNull(changes, "changes");
        if (changes.isEmpty() && baseRoot.isPresent()) {
            return baseRoot.orElseThrow();
        }

        DimensionTree base = baseRoot.isPresent()
                ? objects.readDimension(baseRoot.orElseThrow())
                : new DimensionTree(Map.of());
        Map<RegionCoordinate, Map<ChunkInRegion, Map<HistoryKey, ObjectId>>> grouped = group(changes);
        Map<RegionCoordinate, ObjectId> regions = new HashMap<>(base.regions());

        for (var regionChange : grouped.entrySet()) {
            RegionTree oldRegion = regions.containsKey(regionChange.getKey())
                    ? objects.readRegion(regions.get(regionChange.getKey()))
                    : new RegionTree(Map.of());
            Map<ChunkInRegion, ObjectId> chunks = new HashMap<>(oldRegion.chunks());
            for (var chunkChange : regionChange.getValue().entrySet()) {
                ChunkTree oldChunk = chunks.containsKey(chunkChange.getKey())
                        ? objects.readChunk(chunks.get(chunkChange.getKey()))
                        : new ChunkTree(Map.of(), Optional.empty());
                Map<Integer, ObjectId> sections = new HashMap<>(oldChunk.sections());
                Optional<ObjectId> entities = oldChunk.entities();
                for (var change : chunkChange.getValue().entrySet()) {
                    if (change.getKey() instanceof SectionKey section) {
                        sections.put(section.sectionY(), change.getValue());
                    } else {
                        entities = Optional.of(change.getValue());
                    }
                }
                chunks.put(chunkChange.getKey(), objects.write(new ChunkTree(sections, entities)));
            }
            regions.put(regionChange.getKey(), objects.write(new RegionTree(chunks)));
        }
        return objects.write(new DimensionTree(regions));
    }

    private static Map<RegionCoordinate, Map<ChunkInRegion, Map<HistoryKey, ObjectId>>> group(
            Map<HistoryKey, ObjectId> changes) {
        Map<RegionCoordinate, Map<ChunkInRegion, Map<HistoryKey, ObjectId>>> grouped = new HashMap<>();
        for (var change : changes.entrySet()) {
            int chunkX = chunkX(change.getKey());
            int chunkZ = chunkZ(change.getKey());
            RegionCoordinate region = new RegionCoordinate(
                    Math.floorDiv(chunkX, REGION_SIZE), Math.floorDiv(chunkZ, REGION_SIZE));
            ChunkInRegion chunk = new ChunkInRegion(
                    Math.floorMod(chunkX, REGION_SIZE), Math.floorMod(chunkZ, REGION_SIZE));
            grouped.computeIfAbsent(region, ignored -> new HashMap<>())
                    .computeIfAbsent(chunk, ignored -> new HashMap<>())
                    .put(change.getKey(), change.getValue());
        }
        return grouped;
    }

    private static int chunkX(HistoryKey key) {
        return key instanceof SectionKey section ? section.chunkX() : ((EntityChunkKey) key).chunkX();
    }

    private static int chunkZ(HistoryKey key) {
        return key instanceof SectionKey section ? section.chunkZ() : ((EntityChunkKey) key).chunkZ();
    }
}
