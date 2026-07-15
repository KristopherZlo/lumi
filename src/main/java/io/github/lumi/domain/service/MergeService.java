package io.github.lumi.domain.service;

import io.github.lumi.domain.model.BranchRef;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitAuthor;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.CommitKind;
import io.github.lumi.domain.model.CommitStatistics;
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
        CommitId base = graph.nearestCommonAncestor(
                request.current.commit(), request.source.commit());
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
        for (var entry : sourceDifference.entities().entrySet()) {
            EntityChunkKey key = entry.getKey();
            ObjectChange sourceChange = entry.getValue();
            ObjectChange currentChange = currentDifference.entities().get(key);
            ObjectId currentId = currentId(sourceChange, currentChange);
            if (currentId.equals(sourceChange.after())) {
                continue;
            }
            var merged = merge.entities(
                    objects.readEntities(sourceChange.before()),
                    objects.readEntities(currentId),
                    objects.readEntities(sourceChange.after()));
            ObjectId mergedId = objects.write(merged.value());
            if (!mergedId.equals(currentId)) {
                changes.put(key, mergedId);
                totals.entityChunks++;
                totals.entities = Math.addExact(totals.entities, merged.changedEntities());
                totals.conflicts = Math.addExact(totals.conflicts, merged.conflicts());
            }
        }

        Commit current = commits.read(request.current.commit());
        ObjectId tree = trees.update(Optional.of(current.tree()), changes);
        CommitStatistics statistics = new CommitStatistics(
                totals.sections, totals.entityChunks, totals.blocks, totals.entities);
        CommitId commit = commits.write(new Commit(
                tree, List.of(request.current.commit(), request.source.commit()),
                request.author, request.message, request.timestamp, request.workspaceId,
                request.zoneId, CommitKind.MERGE, statistics));
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
