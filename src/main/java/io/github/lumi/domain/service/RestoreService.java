package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.PartialRestorePlan;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.Zone;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

public final class RestoreService {
    private static final int REGION_SIZE = 32;
    private static final Consumer<PreparationProgress> NO_PROGRESS = ignored -> { };
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final OriginStore origins;
    private final Executor merkleReads;

    public RestoreService(
            WorldObjectRepository objects, CommitRepository commits, OriginStore origins) {
        this(objects, commits, origins, Runnable::run);
    }

    public RestoreService(
            WorldObjectRepository objects,
            CommitRepository commits,
            OriginStore origins,
            Executor merkleReads) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.origins = Objects.requireNonNull(origins, "origins");
        this.merkleReads = Objects.requireNonNull(merkleReads, "merkleReads");
    }

    public void requireTargetInWorkspace(CommitId targetCommit, UUID workspaceId)
            throws IOException {
        Objects.requireNonNull(targetCommit, "targetCommit");
        Objects.requireNonNull(workspaceId, "workspaceId");
        if (!commits.read(targetCommit).workspaceId().equals(workspaceId)) {
            throw new IOException("Restore target does not belong to active workspace");
        }
    }

    public PreparedRestore prepare(BranchRef currentRef, CommitId targetCommit) throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                null, false, true, null, NO_PROGRESS);
    }

    public PreparedRestore prepare(
            BranchRef currentRef,
            CommitId targetCommit,
            Consumer<PreparationProgress> progress) throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                null, false, true, null, progress);
    }

    public PreparedRestore prepare(
            BranchRef expectedRef, CommitId sourceCommit, CommitId targetCommit)
            throws IOException {
        return prepare(expectedRef, sourceCommit, targetCommit, NO_PROGRESS);
    }

    public PreparedRestore prepare(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            Consumer<PreparationProgress> progress) throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, null, false, true, null, progress);
    }

    public PreparedRestore prepareWithoutEntities(
            BranchRef currentRef, CommitId targetCommit) throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                null, false, false, null, NO_PROGRESS);
    }

    public PreparedRestore prepareWithoutEntities(
            BranchRef currentRef,
            CommitId targetCommit,
            Consumer<PreparationProgress> progress) throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                null, false, false, null, progress);
    }

    public PreparedRestore prepareWithoutEntities(
            BranchRef expectedRef, CommitId sourceCommit, CommitId targetCommit)
            throws IOException {
        return prepareWithoutEntities(
                expectedRef, sourceCommit, targetCommit, NO_PROGRESS);
    }

    public PreparedRestore prepareWithoutEntities(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            Consumer<PreparationProgress> progress) throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, null, false, false, null, progress);
    }

    public PreparedRestore preparePartial(
            BranchRef currentRef, CommitId targetCommit, BlockBox area, boolean outside)
            throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                Objects.requireNonNull(area, "area"), outside, false, null, NO_PROGRESS);
    }

    public PreparedRestore preparePartial(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            BlockBox area,
            boolean outside) throws IOException {
        return preparePartial(expectedRef, sourceCommit, targetCommit,
                area, outside, NO_PROGRESS);
    }

    public PreparedRestore preparePartial(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            BlockBox area,
            boolean outside,
            Consumer<PreparationProgress> progress) throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, Objects.requireNonNull(area, "area"), outside,
                false, null, progress);
    }

    public PartialRestorePlan planPartial(
            BranchRef currentRef, CommitId targetCommit, BlockAreaTarget area)
            throws IOException {
        Objects.requireNonNull(currentRef, "currentRef");
        return planPartial(
                currentRef, currentRef.commit(), targetCommit, area);
    }

    public PartialRestorePlan planPartial(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            BlockAreaTarget area) throws IOException {
        Objects.requireNonNull(area, "area");
        try (PreparedRestore prepared = preparePartial(
                expectedRef, sourceCommit, targetCommit,
                area.area(), area.outside())) {
            return new PartialRestorePlan(
                    targetCommit, area, prepared.sections().size(),
                    changedBlockCount(prepared));
        }
    }

    public PreparedRestore prepareZone(
            BranchRef currentRef, CommitId targetCommit, ZoneScope scope) throws IOException {
        return prepare(currentRef, currentRef.commit(), targetCommit,
                null, false, true, Objects.requireNonNull(scope, "scope"), NO_PROGRESS);
    }

    public PreparedRestore prepareZone(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            ZoneScope scope) throws IOException {
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, null, false, true,
                Objects.requireNonNull(scope, "scope"), NO_PROGRESS);
    }

    public PreparedRestore prepareZone(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            Zone zone) throws IOException {
        return prepareZone(expectedRef, sourceCommit, targetCommit, zone, NO_PROGRESS);
    }

    public PreparedRestore prepareZone(
            BranchRef expectedRef,
            CommitId sourceCommit,
            CommitId targetCommit,
            Zone zone,
            Consumer<PreparationProgress> progress) throws IOException {
        Objects.requireNonNull(zone, "zone");
        Commit target = commits.read(targetCommit);
        if (!target.workspaceId().equals(zone.workspaceId())
                || !target.zoneId().filter(zone.id()::equals).isPresent()) {
            throw new IOException("Restore target does not belong to zone: " + zone.id());
        }
        return prepare(expectedRef, Objects.requireNonNull(sourceCommit, "sourceCommit"),
                targetCommit, null, false, true, new ZoneScope(zone), progress);
    }

    private PreparedRestore prepare(
            BranchRef currentRef, CommitId sourceCommit, CommitId targetCommit,
            BlockBox area, boolean outside, boolean includeEntities, ZoneScope scope,
            Consumer<PreparationProgress> progress)
            throws IOException {
        Objects.requireNonNull(progress, "progress");
        Commit currentCommit = commits.read(sourceCommit);
        Commit targetCommitValue = commits.read(targetCommit);
        Map<SectionKey, SectionPlan> sections = new HashMap<>();
        Set<EntityChunkKey> entities = new HashSet<>();
        EntityRestorePlanner.Plan entityPlan;
        try (var reader = objects.beginReadSession()) {
            DimensionTree current = reader.readDimension(currentCommit.tree());
            DimensionTree target = reader.readDimension(targetCommitValue.tree());
            var changedRegions = union(current.regions().keySet(), target.regions().keySet())
                    .stream().filter(region -> !Objects.equals(
                            current.regions().get(region), target.regions().get(region)))
                    .toList();
            for (int regionIndex = 0; regionIndex < changedRegions.size(); regionIndex++) {
                RegionCoordinate regionCoordinate = changedRegions.get(regionIndex);
                Optional<ObjectId> currentRegionId =
                        Optional.ofNullable(current.regions().get(regionCoordinate));
                Optional<ObjectId> targetRegionId =
                        Optional.ofNullable(target.regions().get(regionCoordinate));
                RegionTree currentRegion = currentRegionId.isPresent()
                        ? reader.readRegion(currentRegionId.orElseThrow())
                        : new RegionTree(Map.of());
                RegionTree targetRegion = targetRegionId.isPresent()
                        ? reader.readRegion(targetRegionId.orElseThrow())
                        : new RegionTree(Map.of());
                prepareRegion(reader, regionCoordinate, currentRegion, targetRegion,
                        sections, entities, area, outside, includeEntities, scope,
                        regionIndex + 1, changedRegions.size(), progress);
            }
            entityPlan = includeEntities
                    ? new EntityRestorePlanner(objects, reader, commits, origins)
                            .plan(sourceCommit, targetCommit, entities, scope)
                    : new EntityRestorePlanner.Plan(Map.of(), Map.of());
        }
        boolean restorePlayerSpawns = area == null && scope == null;
        var targetReader = new RestorePlanReader(objects);
        var returnReader = new RestorePlanReader(objects);
        var targetSections = new RestorePlanMap<>(
                physicalSectionOrder(sections, SectionPlan::targetId),
                key -> sections.get(key).target(targetReader),
                targetReader);
        var returnSections = new RestorePlanMap<>(
                physicalSectionOrder(sections, SectionPlan::beforeId),
                key -> sections.get(key).before(returnReader),
                returnReader);
        return new PreparedRestore(currentRef, targetCommit,
                targetSections, entityPlan.target(), returnSections, entityPlan.before(),
                restorePlayerSpawns ? targetCommitValue.playerSpawns() : Map.of(),
                restorePlayerSpawns ? currentCommit.playerSpawns() : Map.of(),
                restorePlayerSpawns);
    }

    private List<SectionKey> physicalSectionOrder(
            Map<SectionKey, SectionPlan> sections,
            Function<SectionPlan, ObjectId> id) throws IOException {
        Set<ObjectId> ids = sections.values().stream()
                .map(id)
                .collect(java.util.stream.Collectors.toSet());
        List<ObjectId> orderedIds = objects.physicalReadOrder(ids);
        Map<ObjectId, Integer> rank = new HashMap<>();
        for (int index = 0; index < orderedIds.size(); index++) {
            rank.put(orderedIds.get(index), index);
        }
        return sections.keySet().stream()
                .sorted(Comparator.<SectionKey>comparingInt(
                                key -> rank.get(id.apply(sections.get(key))))
                        .thenComparingInt(SectionKey::chunkX)
                        .thenComparingInt(SectionKey::chunkZ)
                        .thenComparingInt(SectionKey::sectionY))
                .toList();
    }

    private void prepareRegion(
            WorldObjectRepository.ReadSession reader,
            RegionCoordinate regionCoordinate,
            RegionTree currentRegion,
            RegionTree targetRegion,
            Map<SectionKey, SectionPlan> sections,
            Set<EntityChunkKey> entities,
            BlockBox area,
            boolean outside,
            boolean includeEntities,
            ZoneScope scope,
            int regionIndex,
            int regionTotal,
            Consumer<PreparationProgress> progress) throws IOException {
        MerkleReadCache decoded = new MerkleReadCache(reader);
        var changedChunks = union(currentRegion.chunks().keySet(), targetRegion.chunks().keySet())
                .stream().filter(chunk -> !Objects.equals(
                        currentRegion.chunks().get(chunk), targetRegion.chunks().get(chunk)))
                .toList();
        Set<ObjectId> chunkIds = new HashSet<>();
        for (ChunkInRegion chunk : changedChunks) {
            Optional.ofNullable(currentRegion.chunks().get(chunk)).ifPresent(chunkIds::add);
            Optional.ofNullable(targetRegion.chunks().get(chunk)).ifPresent(chunkIds::add);
        }
        decoded.preloadChunks(chunkIds, merkleReads);
        progress.accept(new PreparationProgress(
                regionIndex, regionTotal, 0, changedChunks.size()));
        for (int chunkIndex = 0; chunkIndex < changedChunks.size(); chunkIndex++) {
            ChunkInRegion local = changedChunks.get(chunkIndex);
            Optional<ObjectId> currentId = Optional.ofNullable(currentRegion.chunks().get(local));
            Optional<ObjectId> targetId = Optional.ofNullable(targetRegion.chunks().get(local));
            ChunkTree current = currentId.isPresent()
                    ? decoded.chunk(currentId.orElseThrow())
                    : new ChunkTree(Map.of(), Optional.empty());
            ChunkTree target = targetId.isPresent()
                    ? decoded.chunk(targetId.orElseThrow())
                    : new ChunkTree(Map.of(), Optional.empty());
            int chunkX = regionCoordinate.x() * REGION_SIZE + local.x();
            int chunkZ = regionCoordinate.z() * REGION_SIZE + local.z();
            prepareChunk(reader, chunkX, chunkZ, current, target,
                    sections, entities,
                    area, outside, includeEntities, scope);
            progress.accept(new PreparationProgress(
                    regionIndex, regionTotal, chunkIndex + 1, changedChunks.size()));
        }
    }

    private void prepareChunk(
            WorldObjectRepository.ReadSession reader,
            int chunkX,
            int chunkZ,
            ChunkTree current,
            ChunkTree target,
            Map<SectionKey, SectionPlan> sections,
            Set<EntityChunkKey> entities,
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
                if (resolved.equals(returnId)) {
                    continue;
                }
                if (area != null && !(outside
                        ? !area.intersects(key) : area.contains(key))) {
                    SectionBlob before = reader.readSection(returnId);
                    SectionBlob selected = select(
                            before, reader.readSection(resolved), key, area, outside);
                    if (!selected.equals(before)) {
                        sections.put(key, new SectionPlan(
                                returnId, resolved, selected, before));
                    }
                } else {
                    sections.put(key, new SectionPlan(
                            returnId, resolved, null, null));
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
            ObjectId returnId = current.entities().isPresent()
                    ? current.entities().orElseThrow()
                    : origin(key);
            if (!resolved.equals(returnId)) {
                entities.add(key);
            }
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

    private static long changedBlockCount(PreparedRestore prepared) throws IOException {
        try {
            long changed = 0;
            for (var entry : prepared.sections().entrySet()) {
                SectionBlob after = entry.getValue();
                SectionBlob before = prepared.returnSections().get(entry.getKey());
                for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                    if (!after.blockStates().get(index).equals(before.blockStates().get(index))
                            || !Objects.equals(
                                    after.blockEntities().get(index),
                                    before.blockEntities().get(index))) {
                        changed = Math.addExact(changed, 1);
                    }
                }
            }
            return changed;
        } catch (UncheckedIOException failed) {
            throw failed.getCause();
        }
    }

    private static <T> Set<T> union(Set<T> first, Set<T> second) {
        Set<T> union = new HashSet<>(first);
        union.addAll(second);
        return union;
    }

    public record PreparationProgress(
            int regionIndex,
            int regionTotal,
            int chunkCompleted,
            int chunkTotal) {
        public PreparationProgress {
            if (regionIndex < 1 || regionIndex > regionTotal
                    || chunkCompleted < 0 || chunkCompleted > chunkTotal) {
                throw new IllegalArgumentException(
                        "Invalid Restore preparation progress");
            }
        }
    }

    private record SectionPlan(
            ObjectId beforeId,
            ObjectId targetId,
            SectionBlob selectedTarget,
            SectionBlob selectedBefore) {
        private SectionBlob target(RestorePlanReader objects) throws IOException {
            return selectedTarget == null ? objects.readSection(targetId) : selectedTarget;
        }

        private SectionBlob before(RestorePlanReader objects) throws IOException {
            return selectedBefore == null ? objects.readSection(beforeId) : selectedBefore;
        }
    }

}
