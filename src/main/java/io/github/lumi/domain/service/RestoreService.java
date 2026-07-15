package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
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

public final class RestoreService {
    private static final int REGION_SIZE = 32;
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final OriginStore origins;

    public RestoreService(
            WorldObjectRepository objects, CommitRepository commits, OriginStore origins) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    public PreparedRestore prepare(BranchRef currentRef, CommitId targetCommit) throws IOException {
        DimensionTree current = objects.readDimension(commits.read(currentRef.commit()).tree());
        DimensionTree target = objects.readDimension(commits.read(targetCommit).tree());
        Map<SectionKey, SectionBlob> sections = new HashMap<>();
        Map<EntityChunkKey, EntityChunkBlob> entities = new HashMap<>();
        Map<SectionKey, SectionBlob> returnSections = new HashMap<>();
        Map<EntityChunkKey, EntityChunkBlob> returnEntities = new HashMap<>();
        for (RegionCoordinate regionCoordinate : union(current.regions().keySet(), target.regions().keySet())) {
            Optional<ObjectId> currentRegionId = Optional.ofNullable(current.regions().get(regionCoordinate));
            Optional<ObjectId> targetRegionId = Optional.ofNullable(target.regions().get(regionCoordinate));
            if (currentRegionId.equals(targetRegionId)) {
                continue;
            }
            RegionTree currentRegion = currentRegionId.isPresent()
                    ? objects.readRegion(currentRegionId.orElseThrow()) : new RegionTree(Map.of());
            RegionTree targetRegion = targetRegionId.isPresent()
                    ? objects.readRegion(targetRegionId.orElseThrow()) : new RegionTree(Map.of());
            prepareRegion(regionCoordinate, currentRegion, targetRegion,
                    sections, entities, returnSections, returnEntities);
        }
        return new PreparedRestore(currentRef, targetCommit,
                sections, entities, returnSections, returnEntities);
    }

    private void prepareRegion(
            RegionCoordinate regionCoordinate,
            RegionTree currentRegion,
            RegionTree targetRegion,
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities,
            Map<SectionKey, SectionBlob> returnSections,
            Map<EntityChunkKey, EntityChunkBlob> returnEntities) throws IOException {
        for (ChunkInRegion local : union(currentRegion.chunks().keySet(), targetRegion.chunks().keySet())) {
            Optional<ObjectId> currentId = Optional.ofNullable(currentRegion.chunks().get(local));
            Optional<ObjectId> targetId = Optional.ofNullable(targetRegion.chunks().get(local));
            if (currentId.equals(targetId)) {
                continue;
            }
            ChunkTree current = currentId.isPresent()
                    ? objects.readChunk(currentId.orElseThrow()) : new ChunkTree(Map.of(), Optional.empty());
            ChunkTree target = targetId.isPresent()
                    ? objects.readChunk(targetId.orElseThrow()) : new ChunkTree(Map.of(), Optional.empty());
            int chunkX = regionCoordinate.x() * REGION_SIZE + local.x();
            int chunkZ = regionCoordinate.z() * REGION_SIZE + local.z();
            prepareChunk(chunkX, chunkZ, current, target,
                    sections, entities, returnSections, returnEntities);
        }
    }

    private void prepareChunk(
            int chunkX,
            int chunkZ,
            ChunkTree current,
            ChunkTree target,
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities,
            Map<SectionKey, SectionBlob> returnSections,
            Map<EntityChunkKey, EntityChunkBlob> returnEntities) throws IOException {
        for (int sectionY : union(current.sections().keySet(), target.sections().keySet())) {
            Optional<ObjectId> currentId = Optional.ofNullable(current.sections().get(sectionY));
            Optional<ObjectId> targetId = Optional.ofNullable(target.sections().get(sectionY));
            if (!currentId.equals(targetId)) {
                SectionKey key = new SectionKey(chunkX, sectionY, chunkZ);
                ObjectId resolved = targetId.isPresent() ? targetId.orElseThrow() : origin(key);
                sections.put(key, objects.readSection(resolved));
                ObjectId returnId = currentId.isPresent() ? currentId.orElseThrow() : origin(key);
                returnSections.put(key, objects.readSection(returnId));
            }
        }
        if (!current.entities().equals(target.entities())) {
            EntityChunkKey key = new EntityChunkKey(chunkX, chunkZ);
            ObjectId resolved = target.entities().isPresent()
                    ? target.entities().orElseThrow()
                    : origin(key);
            entities.put(key, objects.readEntities(resolved));
            ObjectId returnId = current.entities().isPresent()
                    ? current.entities().orElseThrow()
                    : origin(key);
            returnEntities.put(key, objects.readEntities(returnId));
        }
    }

    private ObjectId origin(io.github.lumi.domain.model.HistoryKey key) throws IOException {
        return origins.read(key).orElseThrow(() -> new IOException("Missing origin for " + key));
    }

    private static <T> Set<T> union(Set<T> first, Set<T> second) {
        Set<T> union = new HashSet<>(first);
        union.addAll(second);
        return union;
    }

}
