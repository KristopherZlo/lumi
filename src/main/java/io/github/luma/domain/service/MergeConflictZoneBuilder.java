package io.github.luma.domain.service;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.Bounds3i;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.MergeConflictZone;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MergeConflictZoneBuilder {

    List<MergeConflictZone> build(Collection<StoredBlockChange> conflictingChanges) {
        if (conflictingChanges.isEmpty()) {
            return List.of();
        }

        Map<Long, List<StoredBlockChange>> changesByChunk = new LinkedHashMap<>();
        Map<Long, ChunkPoint> chunkPoints = new LinkedHashMap<>();
        for (StoredBlockChange change : conflictingChanges) {
            ChunkPoint chunk = ChunkPoint.from(change.pos());
            long chunkKey = this.chunkKey(chunk);
            changesByChunk.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(change);
            chunkPoints.putIfAbsent(chunkKey, chunk);
        }

        List<Long> sortedChunkKeys = new ArrayList<>(changesByChunk.keySet());
        sortedChunkKeys.sort(Comparator.naturalOrder());
        Set<Long> unvisited = new LinkedHashSet<>(sortedChunkKeys);
        List<MergeConflictZone> zones = new ArrayList<>();
        int zoneIndex = 1;
        while (!unvisited.isEmpty()) {
            long startKey = unvisited.iterator().next();
            unvisited.remove(startKey);

            Deque<Long> frontier = new ArrayDeque<>();
            frontier.add(startKey);
            LinkedHashSet<Long> zoneChunkKeys = new LinkedHashSet<>();
            List<StoredBlockChange> zoneChanges = new ArrayList<>();
            while (!frontier.isEmpty()) {
                long currentKey = frontier.removeFirst();
                zoneChunkKeys.add(currentKey);
                zoneChanges.addAll(changesByChunk.getOrDefault(currentKey, List.of()));

                ChunkPoint chunk = chunkPoints.get(currentKey);
                for (long neighborKey : this.neighborChunkKeys(chunk)) {
                    if (unvisited.remove(neighborKey)) {
                        frontier.addLast(neighborKey);
                    }
                }
            }

            List<ChunkPoint> chunks = zoneChunkKeys.stream()
                    .map(chunkPoints::get)
                    .sorted(Comparator.comparingInt(ChunkPoint::x).thenComparingInt(ChunkPoint::z))
                    .toList();
            List<StoredBlockChange> sortedZoneChanges = zoneChanges.stream()
                    .sorted(Comparator
                            .comparingInt((StoredBlockChange change) -> change.pos().x())
                            .thenComparingInt(change -> change.pos().y())
                            .thenComparingInt(change -> change.pos().z()))
                    .toList();
            zones.add(new MergeConflictZone(
                    "zone-" + zoneIndex,
                    chunks,
                    this.bounds(sortedZoneChanges),
                    sortedZoneChanges
            ));
            zoneIndex += 1;
        }
        return List.copyOf(zones);
    }

    private Bounds3i bounds(List<StoredBlockChange> changes) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (StoredBlockChange change : changes) {
            BlockPoint pos = change.pos();
            minX = Math.min(minX, pos.x());
            minY = Math.min(minY, pos.y());
            minZ = Math.min(minZ, pos.z());
            maxX = Math.max(maxX, pos.x());
            maxY = Math.max(maxY, pos.y());
            maxZ = Math.max(maxZ, pos.z());
        }
        return new Bounds3i(new BlockPoint(minX, minY, minZ), new BlockPoint(maxX, maxY, maxZ));
    }

    private List<Long> neighborChunkKeys(ChunkPoint chunk) {
        return List.of(
                this.chunkKey(new ChunkPoint(chunk.x() - 1, chunk.z())),
                this.chunkKey(new ChunkPoint(chunk.x() + 1, chunk.z())),
                this.chunkKey(new ChunkPoint(chunk.x(), chunk.z() - 1)),
                this.chunkKey(new ChunkPoint(chunk.x(), chunk.z() + 1))
        );
    }

    private long chunkKey(ChunkPoint chunk) {
        return (((long) chunk.x()) << 32) ^ (((long) chunk.z()) & 0xffffffffL);
    }
}
