package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.object.ObjectStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class GarbageCollector {
    private final ObjectStore objects;
    private final ObjectStore commits;
    private final WorldObjectGraph worldGraph;
    private final CommitRepository commitRepository;
    private final BranchRefRepository refs;
    private final OriginStore origins;
    private final TombstoneRepository tombstones;

    public GarbageCollector(Path dimensionRepository) {
        Objects.requireNonNull(dimensionRepository, "dimensionRepository");
        objects = new ObjectStore(dimensionRepository.resolve("objects"));
        commits = new ObjectStore(dimensionRepository.resolve("commits"));
        worldGraph = new WorldObjectGraph(new WorldObjectRepository(dimensionRepository));
        commitRepository = new CommitRepository(dimensionRepository);
        refs = new BranchRefRepository(dimensionRepository);
        origins = new OriginStore(dimensionRepository);
        tombstones = new TombstoneRepository(dimensionRepository);
    }

    public GarbageCollectionResult collect(Set<CommitId> retainedCommits, Instant deleteBefore) throws IOException {
        Objects.requireNonNull(retainedCommits, "retainedCommits");
        Objects.requireNonNull(deleteBefore, "deleteBefore");
        Set<CommitId> roots = new HashSet<>(retainedCommits);
        refs.list().forEach(ref -> roots.add(ref.commit()));
        tombstones.list().forEach(tombstone -> roots.add(tombstone.commit()));
        Set<ObjectId> allCommitObjects = commits.listIds();
        for (ObjectId id : allCommitObjects) {
            if (!commits.modifiedAt(id).isBefore(deleteBefore)) {
                roots.add(new CommitId(id));
            }
        }

        Set<CommitId> reachableCommits = new HashSet<>();
        Set<ObjectId> reachableObjects = new HashSet<>(origins.allOrigins());
        ArrayDeque<CommitId> pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            CommitId id = pending.removeFirst();
            if (!reachableCommits.add(id)) {
                continue;
            }
            var commit = commitRepository.read(id);
            pending.addAll(commit.parents());
            reachableObjects.addAll(worldGraph.scan(commit.tree()).reachable());
        }

        int deletedObjects = 0;
        for (ObjectId id : objects.listIds()) {
            if (!reachableObjects.contains(id) && objects.modifiedAt(id).isBefore(deleteBefore)) {
                objects.delete(id);
                deletedObjects++;
            }
        }
        int deletedCommits = 0;
        for (ObjectId id : allCommitObjects) {
            if (!reachableCommits.contains(new CommitId(id)) && commits.modifiedAt(id).isBefore(deleteBefore)) {
                commits.delete(id);
                deletedCommits++;
            }
        }
        return new GarbageCollectionResult(deletedCommits, deletedObjects);
    }

}
