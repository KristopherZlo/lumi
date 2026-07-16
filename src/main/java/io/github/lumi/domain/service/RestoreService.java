package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
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
import java.util.ArrayList;
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
        return prepare(currentRef, currentRef.commit(), targetCommit, null, false, true, null);
    }

    public PreparedRestore prepare(
            BranchRef expectedRef, CommitId sourceCommit, CommitId targetCommit)
            throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, null, false, true, null);
    }

    public PreparedRestore prepareWithoutEntities(
            BranchRef currentRef, CommitId targetCommit) throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit, null, false, false, null);
    }

    public PreparedRestore prepareWithoutEntities(
            BranchRef expectedRef, CommitId sourceCommit, CommitId targetCommit)
            throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, null, false, false, null);
    }

    public PreparedRestore preparePartial(
            BranchRef currentRef, CommitId targetCommit, BlockBox area, boolean outside)
            throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                Objects.requireNonNull(area, "area"), outside, false, null);
    }

    public PreparedRestore preparePartial(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            BlockBox area,
            boolean outside) throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, Objects.requireNonNull(area, "area"), outside, false, null);
    }

    public PreparedRestore prepareZone(
            BranchRef currentRef, CommitId targetCommit, ZoneScope scope) throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                null, false, true, Objects.requireNonNull(scope, "scope"));
    }

    public PreparedRestore prepareZone(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            ZoneScope scope) throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, null, false, true, Objects.requireNonNull(scope, "scope"));
    }

    private PreparedRestore prepare(
            BranchRef currentRef, CommitId sourceCommit, CommitId targetCommit,
            BlockBox area, boolean outside, boolean includeEntities, ZoneScope scope)
            throws IOException {
        Commit currentCommit = commits.read(sourceCommit);
        Commit targetCommitValue = commits.read(targetCommit);
        DimensionTree current = objects.readDimension(currentCommit.tree());
        DimensionTree target = objects.readDimension(targetCommitValue.tree());
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
                    sections, entities, returnSections, returnEntities,
                    area, outside, includeEntities, scope);
        }
        boolean restorePlayerSpawns = area == null && scope == null;
        return new PreparedRestore(currentRef, targetCommit,
                sections, entities, returnSections, returnEntities,
                restorePlayerSpawns ? targetCommitValue.playerSpawns() : Map.of(),
                restorePlayerSpawns ? currentCommit.playerSpawns() : Map.of(),
                restorePlayerSpawns);
    }

    private void prepareRegion(
            RegionCoordinate regionCoordinate,
            RegionTree currentRegion,
            RegionTree targetRegion,
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities,
            Map<SectionKey, SectionBlob> returnSections,
            Map<EntityChunkKey, EntityChunkBlob> returnEntities,
            BlockBox area,
            boolean outside,
            boolean includeEntities,
            ZoneScope scope) throws IOException {
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
                    sections, entities, returnSections, returnEntities,
                    area, outside, includeEntities, scope);
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
            Map<EntityChunkKey, EntityChunkBlob> returnEntities,
            BlockBox area,
            boolean outside,
            boolean includeEntities,
            ZoneScope scope) throws IOException {
        for (int sectionY : union(current.sections().keySet(), target.sections().keySet())) {
            Optional<ObjectId> currentId = Optional.ofNullable(current.sections().get(sectionY));
            Optional<ObjectId> targetId = Optional.ofNullable(target.sections().get(sectionY));
            if (!currentId.equals(targetId)) {
                SectionKey key = new SectionKey(chunkX, sectionY, chunkZ);
                if (area != null && !selectsSection(area, key, outside)) continue;
                if (scope != null && !scope.includes(key)) continue;
                ObjectId returnId = currentId.isPresent() ? currentId.orElseThrow() : origin(key);
                ObjectId resolved = targetId.isPresent() ? targetId.orElseThrow() : origin(key);
                SectionBlob before = objects.readSection(returnId);
                SectionBlob after = objects.readSection(resolved);
                SectionBlob selected = area == null ? after : select(before, after, key, area, outside);
                if (!selected.equals(before)) {
                    sections.put(key, selected);
                    returnSections.put(key, before);
                }
            }
        }
        if (area == null && includeEntities
                && !current.entities().equals(target.entities())) {
            EntityChunkKey key = new EntityChunkKey(chunkX, chunkZ);
            if (scope != null && !scope.includes(key)) return;
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

    private static boolean selectsSection(BlockBox area, SectionKey key, boolean outside) {
        return outside ? !area.contains(key) : area.intersects(key);
    }

    private static SectionBlob select(
            SectionBlob before, SectionBlob after, SectionKey key,
            BlockBox area, boolean outside) {
        boolean fullSection = outside ? !area.intersects(key) : area.contains(key);
        if (fullSection) return after;
        var blocks = new ArrayList<>(before.blockStates());
        var blockEntities = new HashMap<>(before.blockEntities());
        int baseX = key.chunkX() * 16;
        int baseY = key.sectionY() * 16;
        int baseZ = key.chunkZ() * 16;
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            int x = index & 15;
            int z = index >> 4 & 15;
            int y = index >> 8 & 15;
            if (outside != area.contains(baseX + x, baseY + y, baseZ + z)) {
                blocks.set(index, after.blockStates().get(index));
                blockEntities.remove(index);
                if (after.blockEntities().containsKey(index)) {
                    blockEntities.put(index, after.blockEntities().get(index));
                }
            }
        }
        return new SectionBlob(blocks, blockEntities);
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
