package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Consumer;

/** Builds an exact target-block/current-entity commit without copying block leaves. */
public final class BlockOnlyRestoreService {
    private static final int REGION_SIZE = 32;
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final OriginStore origins;

    public BlockOnlyRestoreService(
            WorldObjectRepository objects,
            CommitRepository commits,
            OriginStore origins) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    public CommitId compose(
            CommitId checkpoint,
            CommitId target,
            CommitAuthor author,
            Instant timestamp,
            Consumer<RestoreService.PreparationProgress> progress)
            throws IOException {
        Objects.requireNonNull(checkpoint, "checkpoint");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(progress, "progress");
        Commit checkpointCommit = commits.read(checkpoint);
        Commit targetCommit = commits.read(target);
        if (!checkpointCommit.workspaceId().equals(targetCommit.workspaceId())) {
            throw new IOException("Block-only Restore commits belong to different workspaces");
        }
        DimensionTree checkpointTree = objects.readDimension(checkpointCommit.tree());
        DimensionTree targetTree = objects.readDimension(targetCommit.tree());
        Map<RegionCoordinate, ObjectId> regions = new HashMap<>(targetTree.regions());
        var changedRegions = new TreeSet<>(checkpointTree.regions().keySet());
        changedRegions.addAll(targetTree.regions().keySet());
        changedRegions.removeIf(region -> Objects.equals(
                checkpointTree.regions().get(region), targetTree.regions().get(region)));
        Totals totals = new Totals();
        ObjectId tree;
        try (WorldObjectRepository.WriteBatch batch = objects.beginBatch()) {
            int regionIndex = 0;
            for (RegionCoordinate coordinate : changedRegions) {
                composeRegion(
                        coordinate, checkpointTree, targetTree, regions, batch,
                        ++regionIndex, changedRegions.size(), totals, progress);
            }
            if (totals.entityChunks == 0) {
                return target;
            }
            tree = batch.write(new DimensionTree(regions));
            batch.publish();
        }
        return commits.write(new Commit(
                tree, List.of(target, checkpoint), author,
                "Restore blocks from " + targetCommit.message(), timestamp,
                targetCommit.workspaceId(), Optional.empty(), CommitKind.RESTORE,
                new CommitStatistics(
                        0, totals.entityChunks, 0, totals.entities),
                targetCommit.playerSpawns()));
    }

    private void composeRegion(
            RegionCoordinate coordinate,
            DimensionTree checkpointTree,
            DimensionTree targetTree,
            Map<RegionCoordinate, ObjectId> result,
            WorldObjectRepository.WriteBatch batch,
            int regionIndex,
            int regionTotal,
            Totals totals,
            Consumer<RestoreService.PreparationProgress> progress)
            throws IOException {
        RegionTree checkpoint = readRegion(checkpointTree.regions().get(coordinate));
        RegionTree target = readRegion(targetTree.regions().get(coordinate));
        Map<ChunkInRegion, ObjectId> chunks = new HashMap<>(target.chunks());
        var changedChunks = new TreeSet<>(checkpoint.chunks().keySet());
        changedChunks.addAll(target.chunks().keySet());
        changedChunks.removeIf(chunk -> Objects.equals(
                checkpoint.chunks().get(chunk), target.chunks().get(chunk)));
        progress.accept(new RestoreService.PreparationProgress(
                regionIndex, regionTotal, 0, changedChunks.size()));
        int completed = 0;
        for (ChunkInRegion coordinateInRegion : changedChunks) {
            ChunkTree checkpointChunk = readChunk(
                    checkpoint.chunks().get(coordinateInRegion));
            ChunkTree targetChunk = readChunk(target.chunks().get(coordinateInRegion));
            if (!sameEntities(
                    coordinate, coordinateInRegion,
                    checkpointChunk.entities(), targetChunk.entities())) {
                ChunkTree composed = new ChunkTree(
                        targetChunk.sections(), checkpointChunk.entities());
                if (composed.sections().isEmpty() && composed.entities().isEmpty()) {
                    chunks.remove(coordinateInRegion);
                } else {
                    chunks.put(coordinateInRegion, batch.write(composed));
                }
                ObjectId entities = resolve(
                        entityKey(coordinate, coordinateInRegion),
                        checkpointChunk.entities());
                totals.entityChunks = Math.incrementExact(totals.entityChunks);
                totals.entities = Math.addExact(
                        totals.entities, objects.readEntities(entities).entities().size());
            }
            progress.accept(new RestoreService.PreparationProgress(
                    regionIndex, regionTotal, ++completed, changedChunks.size()));
        }
        if (chunks.equals(target.chunks())) {
            return;
        }
        if (chunks.isEmpty()) {
            result.remove(coordinate);
        } else {
            result.put(coordinate, batch.write(new RegionTree(chunks)));
        }
    }

    private boolean sameEntities(
            RegionCoordinate region,
            ChunkInRegion chunk,
            Optional<ObjectId> checkpoint,
            Optional<ObjectId> target) throws IOException {
        return checkpoint.equals(target)
                || resolve(entityKey(region, chunk), checkpoint)
                        .equals(resolve(entityKey(region, chunk), target));
    }

    private ObjectId resolve(EntityChunkKey key, Optional<ObjectId> value)
            throws IOException {
        return value.isPresent() ? value.orElseThrow() : origins.read(key).orElseThrow(
                () -> new IOException("Missing origin for " + key));
    }

    private RegionTree readRegion(ObjectId id) throws IOException {
        return id == null ? new RegionTree(Map.of()) : objects.readRegion(id);
    }

    private ChunkTree readChunk(ObjectId id) throws IOException {
        return id == null
                ? new ChunkTree(Map.of(), Optional.empty())
                : objects.readChunk(id);
    }

    private static EntityChunkKey entityKey(
            RegionCoordinate region, ChunkInRegion chunk) {
        return new EntityChunkKey(
                region.x() * REGION_SIZE + chunk.x(),
                region.z() * REGION_SIZE + chunk.z());
    }

    private static final class Totals {
        private int entityChunks;
        private int entities;
    }
}
