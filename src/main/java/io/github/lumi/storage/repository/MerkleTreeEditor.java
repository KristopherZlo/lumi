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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;

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
        try (WorldObjectRepository.WriteBatch batch = objects.beginBatch()) {
            ObjectId root = update(baseRoot, changes, batch);
            batch.publish();
            return root;
        }
    }

    public ObjectId update(
            Optional<ObjectId> baseRoot,
            Map<HistoryKey, ObjectId> changes,
            WorldObjectRepository.WriteBatch batch) throws IOException {
        return update(baseRoot, changes, batch, (completed, total) -> { });
    }

    public ObjectId update(
            Optional<ObjectId> baseRoot,
            Map<HistoryKey, ObjectId> changes,
            WorldObjectRepository.WriteBatch batch,
            BiConsumer<Long, Long> progress) throws IOException {
        Objects.requireNonNull(baseRoot, "baseRoot");
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(progress, "progress");
        if (changes.isEmpty() && baseRoot.isPresent()) {
            progress.accept(1L, 1L);
            return baseRoot.orElseThrow();
        }
        try (var reader = objects.beginReadSession()) {
            var chunkReader = new CachedChunkTreeReader(reader::readChunk);
            DimensionTree base = baseRoot.isPresent()
                    ? reader.readDimension(baseRoot.orElseThrow())
                    : new DimensionTree(Map.of());
            Map<RegionCoordinate, Map<ChunkInRegion, Map<HistoryKey, ObjectId>>> grouped =
                    group(changes);
            Map<RegionCoordinate, ObjectId> regions = new HashMap<>(base.regions());
            long total = grouped.values().stream().mapToLong(Map::size).sum()
                    + grouped.size() + 1L;
            long completed = 0;
            progress.accept(completed, total);

            for (var regionChange : grouped.entrySet()) {
                RegionTree oldRegion = regions.containsKey(regionChange.getKey())
                        ? reader.readRegion(regions.get(regionChange.getKey()))
                        : new RegionTree(Map.of());
                Map<ChunkInRegion, ObjectId> chunks = new HashMap<>(oldRegion.chunks());
                for (var chunkChange : orderedChanges(regionChange.getValue(), chunks)) {
                    ChunkTree oldChunk = chunks.containsKey(chunkChange.getKey())
                            ? chunkReader.read(chunks.get(chunkChange.getKey()))
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
                    chunks.put(chunkChange.getKey(), batch.write(
                            new ChunkTree(sections, entities)));
                    progress.accept(++completed, total);
                }
                regions.put(regionChange.getKey(), batch.write(new RegionTree(chunks)));
                progress.accept(++completed, total);
            }
            ObjectId root = batch.write(new DimensionTree(regions));
            progress.accept(++completed, total);
            return root;
        }
    }

    /** At most one region (1024 chunks) is sorted; no extra decode workers are added. */
    private java.util.List<Map.Entry<ChunkInRegion, Map<HistoryKey, ObjectId>>> orderedChanges(
            Map<ChunkInRegion, Map<HistoryKey, ObjectId>> changes,
            Map<ChunkInRegion, ObjectId> chunks) throws IOException {
        var ids = new HashSet<ObjectId>();
        changes.keySet().forEach(chunk -> {
            ObjectId id = chunks.get(chunk);
            if (id != null) ids.add(id);
        });
        var positions = new HashMap<ObjectId, Integer>();
        var ordered = objects.physicalReadOrder(ids);
        for (int index = 0; index < ordered.size(); index++) {
            positions.put(ordered.get(index), index);
        }
        var result = new ArrayList<>(changes.entrySet());
        result.sort(Comparator.comparingInt(
                entry -> positions.getOrDefault(chunks.get(entry.getKey()), Integer.MAX_VALUE)));
        return result;
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
