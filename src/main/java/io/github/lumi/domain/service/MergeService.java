package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectChange;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Prepares an immutable two-parent merge commit without moving a branch ref. */
public final class MergeService {
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final MerkleTreeEditor trees;
    private final CompareService compare;
    private final CommitGraph graph;
    private final ThreeWayMerge merge = new ThreeWayMerge();

    public MergeService(
            WorldObjectRepository objects,
            CommitRepository commits,
            OriginStore origins,
            MerkleTreeEditor trees) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.trees = Objects.requireNonNull(trees, "trees");
        compare = new CompareService(objects, commits, Objects.requireNonNull(origins, "origins"));
        graph = new CommitGraph(commits);
    }

    public Result prepare(Request request) throws IOException {
        Objects.requireNonNull(request, "request");
        Commit currentCommit = commits.read(request.current.commit());
        Commit sourceCommit = commits.read(request.source.commit());
        if (!currentCommit.workspaceId().equals(request.workspaceId)
                || !sourceCommit.workspaceId().equals(request.workspaceId)) {
            throw new IOException("Merge branches do not belong to the requested workspace");
        }
        CommitId base = graph.nearestCommonAncestor(
                request.current.commit(), request.source.commit());
        Commit baseCommit = commits.read(base);
        var currentDifference = compare.compare(base, request.current.commit());
        var sourceDifference = compare.compare(base, request.source.commit());
        Map<HistoryKey, ObjectId> changes = new HashMap<>();
        Accumulator totals = new Accumulator();

        for (var entry : sourceDifference.sections().entrySet()) {
            SectionKey key = entry.getKey();
            ObjectChange sourceChange = entry.getValue();
            ObjectChange currentChange = currentDifference.sections().get(key);
            ObjectId currentId = currentId(sourceChange, currentChange);
            if (currentId.equals(sourceChange.after())) {
                continue;
            }
            var merged = merge.sections(
                    objects.readSection(sourceChange.before()),
                    objects.readSection(currentId),
                    objects.readSection(sourceChange.after()));
            ObjectId mergedId = objects.write(merged.value());
            if (!mergedId.equals(currentId)) {
                changes.put(key, mergedId);
                totals.sections++;
                totals.blocks = Math.addExact(totals.blocks, merged.changedBlocks());
                totals.conflicts = Math.addExact(totals.conflicts, merged.conflicts());
            }
        }
        if (!sourceDifference.entities().isEmpty()) {
            var keys = new HashSet<EntityChunkKey>();
            keys.addAll(currentDifference.entities().keySet());
            keys.addAll(sourceDifference.entities().keySet());
            Map<EntityChunkKey, EntityChunkBlob> baseEntities = new HashMap<>();
            Map<EntityChunkKey, EntityChunkBlob> currentEntities = new HashMap<>();
            Map<EntityChunkKey, EntityChunkBlob> sourceEntities = new HashMap<>();
            Map<EntityChunkKey, ObjectId> currentIds = new HashMap<>();
            for (EntityChunkKey key : keys) {
                ObjectChange currentChange = currentDifference.entities().get(key);
                ObjectChange sourceChange = sourceDifference.entities().get(key);
                ObjectId baseId = baseId(currentChange, sourceChange);
                ObjectId currentId = currentChange == null ? baseId : currentChange.after();
                ObjectId sourceId = sourceChange == null ? baseId : sourceChange.after();
                baseEntities.put(key, objects.readEntities(baseId));
                currentEntities.put(key, objects.readEntities(currentId));
                sourceEntities.put(key, objects.readEntities(sourceId));
                currentIds.put(key, currentId);
            }
            var merged = merge.entityChunks(baseEntities, currentEntities, sourceEntities);
            for (EntityChunkKey key : keys) {
                ObjectId mergedId = objects.write(merged.value().get(key));
                if (!mergedId.equals(currentIds.get(key))) {
                    changes.put(key, mergedId);
                    totals.entityChunks++;
                }
            }
            totals.entities = Math.addExact(totals.entities, merged.changedEntities());
            totals.conflicts = Math.addExact(totals.conflicts, merged.conflicts());
        }

        var playerSpawns = merge.playerSpawns(
                baseCommit.playerSpawns(), currentCommit.playerSpawns(),
                sourceCommit.playerSpawns());
        totals.conflicts = Math.addExact(totals.conflicts, playerSpawns.conflicts());
        ObjectId tree = trees.update(Optional.of(currentCommit.tree()), changes);
        CommitStatistics statistics = new CommitStatistics(
                totals.sections, totals.entityChunks, totals.blocks, totals.entities);
        CommitId commit = commits.write(new Commit(
                tree, List.of(request.current.commit(), request.source.commit()),
                request.author, request.message, request.timestamp, request.workspaceId,
                request.zoneId, CommitKind.MERGE, statistics, playerSpawns.value()));
        return new Result(base, commit, totals.conflicts, statistics);
    }

    private static ObjectId currentId(ObjectChange source, ObjectChange current)
            throws IOException {
        if (current == null) {
            return source.before();
        }
        if (!current.before().equals(source.before())) {
            throw new IOException("Merge comparisons resolved different base objects");
        }
        return current.after();
    }

    private static ObjectId baseId(ObjectChange current, ObjectChange source)
            throws IOException {
        ObjectId base = current == null ? source.before() : current.before();
        if (source != null && !source.before().equals(base)) {
            throw new IOException("Merge comparisons resolved different base objects");
        }
        return base;
    }

    public record Request(
            BranchRef current,
            BranchRef source,
            CommitAuthor author,
            String message,
            Instant timestamp,
            UUID workspaceId,
            Optional<UUID> zoneId) {
        public Request {
            Objects.requireNonNull(current, "current");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(author, "author");
            if (Objects.requireNonNull(message, "message").isBlank()) {
                throw new IllegalArgumentException("Merge message cannot be blank");
            }
            Objects.requireNonNull(timestamp, "timestamp");
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(zoneId, "zoneId");
            if (current.name().equals(source.name())) {
                throw new IllegalArgumentException("Merge source must be another branch");
            }
        }
    }

    public record Result(
            CommitId base, CommitId commit, int conflicts, CommitStatistics statistics) { }

    private static final class Accumulator {
        private int sections;
        private int entityChunks;
        private long blocks;
        private int entities;
        private int conflicts;
    }
}
