package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectChange;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldDifference;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Compares commit trees by identity and resolves only sparse missing leaves through origin. */
public final class CompareService {
    private static final int REGION_SIZE = 32;
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final OriginStore origins;

    public CompareService(
            WorldObjectRepository objects, CommitRepository commits, OriginStore origins) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    public WorldDifference compare(CommitId before, CommitId after) throws IOException {
        return compare(before, after, null, () -> false);
    }

    public WorldDifference compare(
            CommitId before, CommitId after, BooleanSupplier cancelled) throws IOException {
        return compare(before, after, null, cancelled);
    }

    public WorldDifference compare(CommitId before, CommitId after, ZoneScope scope)
            throws IOException {
        return compare(before, after, scope, () -> false);
    }

    private WorldDifference compare(
            CommitId before,
            CommitId after,
            ZoneScope scope,
            BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(cancelled, "cancelled");
        checkCancelled(cancelled);
        if (before.equals(after)) {
            return new WorldDifference(Map.of(), Map.of());
        }
        DimensionTree left = objects.readDimension(commits.read(before).tree());
        DimensionTree right = objects.readDimension(commits.read(after).tree());
        Map<SectionKey, ObjectChange> sections = new HashMap<>();
        Map<EntityChunkKey, ObjectChange> entities = new HashMap<>();
        for (RegionCoordinate coordinate : union(left.regions().keySet(), right.regions().keySet())) {
            checkCancelled(cancelled);
            Optional<ObjectId> leftId = Optional.ofNullable(left.regions().get(coordinate));
            Optional<ObjectId> rightId = Optional.ofNullable(right.regions().get(coordinate));
            if (leftId.equals(rightId)) continue;
            RegionTree leftRegion = leftId.isPresent()
                    ? objects.readRegion(leftId.orElseThrow()) : new RegionTree(Map.of());
            RegionTree rightRegion = rightId.isPresent()
                    ? objects.readRegion(rightId.orElseThrow()) : new RegionTree(Map.of());
            compareRegion(
                    coordinate, leftRegion, rightRegion, sections, entities, scope, cancelled);
        }
        return new WorldDifference(sections, entities);
    }

    private void compareRegion(
            RegionCoordinate region,
            RegionTree left,
            RegionTree right,
            Map<SectionKey, ObjectChange> sections,
            Map<EntityChunkKey, ObjectChange> entities,
            ZoneScope scope,
            BooleanSupplier cancelled) throws IOException {
        for (ChunkInRegion local : union(left.chunks().keySet(), right.chunks().keySet())) {
            checkCancelled(cancelled);
            Optional<ObjectId> leftId = Optional.ofNullable(left.chunks().get(local));
            Optional<ObjectId> rightId = Optional.ofNullable(right.chunks().get(local));
            if (leftId.equals(rightId)) continue;
            ChunkTree leftChunk = leftId.isPresent()
                    ? objects.readChunk(leftId.orElseThrow()) : new ChunkTree(Map.of(), Optional.empty());
            ChunkTree rightChunk = rightId.isPresent()
                    ? objects.readChunk(rightId.orElseThrow()) : new ChunkTree(Map.of(), Optional.empty());
            int chunkX = region.x() * REGION_SIZE + local.x();
            int chunkZ = region.z() * REGION_SIZE + local.z();
            compareChunk(
                    chunkX, chunkZ, leftChunk, rightChunk,
                    sections, entities, scope, cancelled);
        }
    }

    private void compareChunk(
            int chunkX,
            int chunkZ,
            ChunkTree left,
            ChunkTree right,
            Map<SectionKey, ObjectChange> sections,
            Map<EntityChunkKey, ObjectChange> entities,
            ZoneScope scope,
            BooleanSupplier cancelled) throws IOException {
        for (int sectionY : union(left.sections().keySet(), right.sections().keySet())) {
            checkCancelled(cancelled);
            SectionKey key = new SectionKey(chunkX, sectionY, chunkZ);
            if (scope != null && !scope.includes(key)) continue;
            addIfChanged(sections, key,
                    Optional.ofNullable(left.sections().get(sectionY)),
                    Optional.ofNullable(right.sections().get(sectionY)));
        }
        EntityChunkKey entityKey = new EntityChunkKey(chunkX, chunkZ);
        if (scope != null && !scope.includes(entityKey)) return;
        addIfChanged(entities, entityKey, left.entities(), right.entities());
    }

    private <K extends HistoryKey> void addIfChanged(
            Map<K, ObjectChange> changes,
            K key,
            Optional<ObjectId> left,
            Optional<ObjectId> right) throws IOException {
        if (left.equals(right)) return;
        ObjectId resolvedLeft = resolve(key, left);
        ObjectId resolvedRight = resolve(key, right);
        if (!resolvedLeft.equals(resolvedRight)) {
            changes.put(key, new ObjectChange(resolvedLeft, resolvedRight));
        }
    }

    private ObjectId resolve(HistoryKey key, Optional<ObjectId> object) throws IOException {
        if (object.isPresent()) {
            return object.orElseThrow();
        }
        return origins.read(key).orElseThrow(
                () -> new IOException("Missing origin for " + key));
    }

    private static <T> Set<T> union(Set<T> first, Set<T> second) {
        Set<T> union = new HashSet<>(first);
        union.addAll(second);
        return union;
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Compare cancelled");
        }
    }
}
