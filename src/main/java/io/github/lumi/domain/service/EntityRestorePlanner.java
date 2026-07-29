package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectGraph;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Expands sparse entity changes into identity-complete Restore state. */
final class EntityRestorePlanner {
    private static final EntityChunkBlob EMPTY = new EntityChunkBlob(List.of());
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final OriginStore origins;
    private final Map<CommitId, Snapshot> snapshots = new HashMap<>();
    private final Map<ObjectId, EntityChunkBlob> blobs = new HashMap<>();
    private final Map<SelectionKey, Optional<Placement>> selections = new HashMap<>();
    private Map<EntityChunkKey, ObjectId> entityOrigins;

    EntityRestorePlanner(
            WorldObjectRepository objects, CommitRepository commits, OriginStore origins) {
        this.objects = Objects.requireNonNull(objects, "objects");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.origins = Objects.requireNonNull(origins, "origins");
    }

    Plan plan(
            CommitId before,
            CommitId target,
            Set<EntityChunkKey> changed,
            ZoneScope scope) throws IOException {
        if (changed.isEmpty()) {
            return new Plan(Map.of(), Map.of());
        }
        Snapshot beforeState = snapshot(before);
        Snapshot targetState = snapshot(target);
        Set<EntityChunkKey> keys = new HashSet<>(changed);
        Set<UUID> identities = new HashSet<>();
        int previousKeyCount;
        int previousIdentityCount;
        do {
            previousKeyCount = keys.size();
            previousIdentityCount = identities.size();
            for (EntityChunkKey key : List.copyOf(keys)) {
                beforeState.chunks.getOrDefault(key, EMPTY).entities()
                        .forEach(entity -> identities.add(entity.id()));
                targetState.chunks.getOrDefault(key, EMPTY).entities()
                        .forEach(entity -> identities.add(entity.id()));
            }
            for (UUID identity : identities) {
                beforeState.placements.getOrDefault(identity, List.of())
                        .forEach(placement -> keys.add(placement.chunk));
                targetState.placements.getOrDefault(identity, List.of())
                        .forEach(placement -> keys.add(placement.chunk));
            }
        } while (keys.size() != previousKeyCount
                || identities.size() != previousIdentityCount);

        Map<UUID, Placement> beforeSelection = select(before, identities);
        Map<UUID, Placement> targetSelection = select(target, identities);
        Map<EntityChunkKey, EntityChunkBlob> beforeChunks = new HashMap<>();
        Map<EntityChunkKey, EntityChunkBlob> targetChunks = new HashMap<>();
        for (EntityChunkKey key : keys) {
            EntityChunkBlob storedBefore = requireChunk(beforeState, key, before);
            EntityChunkBlob beforeChunk =
                    canonical(storedBefore, key, identities, beforeSelection);
            EntityChunkBlob targetChunk = canonical(
                    requireChunk(targetState, key, target), key, identities, targetSelection);
            if (scope != null && !scope.includes(key)) {
                throw new IOException("Entity Restore crosses zone boundary at " + key);
            }
            beforeChunks.put(key, beforeChunk);
            targetChunks.put(key, targetChunk);
        }
        return new Plan(targetChunks, beforeChunks);
    }

    private Map<UUID, Placement> select(CommitId commit, Set<UUID> identities)
            throws IOException {
        Map<UUID, Placement> selected = new HashMap<>();
        for (UUID identity : identities) {
            resolve(commit, identity).ifPresent(value -> selected.put(identity, value));
        }
        return selected;
    }

    private Optional<Placement> resolve(CommitId commitId, UUID identity) throws IOException {
        SelectionKey key = new SelectionKey(commitId, identity);
        if (selections.containsKey(key)) {
            return selections.get(key);
        }
        Snapshot snapshot = snapshot(commitId);
        List<Placement> candidates = snapshot.placements.getOrDefault(identity, List.of());
        Optional<Placement> selected = candidates.stream().findFirst();
        if (candidates.size() > 1) {
            var commit = commits.read(commitId);
            if (commit.parents().size() != 1) {
                throw ambiguous(commitId, identity, candidates);
            }
            CommitId parentId = commit.parents().getFirst();
            Snapshot parent = snapshot(parentId);
            List<Placement> parentPlacements =
                    parent.placements.getOrDefault(identity, List.of());
            List<Placement> introduced = candidates.stream()
                    .filter(candidate -> !parentPlacements.contains(candidate))
                    .toList();
            if (introduced.size() == 1) {
                selected = Optional.of(introduced.getFirst());
            } else if (introduced.isEmpty()) {
                selected = resolve(parentId, identity).filter(candidates::contains);
                if (selected.isEmpty()) {
                    throw ambiguous(commitId, identity, candidates);
                }
            } else {
                throw ambiguous(commitId, identity, candidates);
            }
        }
        selections.put(key, selected);
        return selected;
    }

    private Snapshot snapshot(CommitId id) throws IOException {
        Snapshot cached = snapshots.get(id);
        if (cached != null) {
            return cached;
        }
        Map<EntityChunkKey, ObjectId> ids = new HashMap<>(entityOrigins());
        for (var entry : new WorldObjectGraph(objects)
                .scan(commits.read(id).tree()).leaves().entrySet()) {
            if (entry.getKey() instanceof EntityChunkKey key) {
                ids.put(key, entry.getValue());
            }
        }
        Map<EntityChunkKey, EntityChunkBlob> chunks = new HashMap<>();
        Map<UUID, List<Placement>> placements = new HashMap<>();
        for (var entry : ids.entrySet()) {
            EntityChunkBlob chunk = blob(entry.getValue());
            chunks.put(entry.getKey(), chunk);
            for (EntityState entity : chunk.entities()) {
                placements.computeIfAbsent(entity.id(), ignored -> new ArrayList<>())
                        .add(new Placement(entry.getKey(), entity));
            }
        }
        Snapshot result = new Snapshot(chunks, placements);
        snapshots.put(id, result);
        return result;
    }

    private Map<EntityChunkKey, ObjectId> entityOrigins() throws IOException {
        if (entityOrigins == null) {
            entityOrigins = origins.entityEntries();
        }
        return entityOrigins;
    }

    private EntityChunkBlob blob(ObjectId id) throws IOException {
        EntityChunkBlob cached = blobs.get(id);
        if (cached == null) {
            cached = objects.readEntities(id);
            blobs.put(id, cached);
        }
        return cached;
    }

    private static EntityChunkBlob requireChunk(
            Snapshot snapshot, EntityChunkKey key, CommitId commit) throws IOException {
        EntityChunkBlob chunk = snapshot.chunks.get(key);
        if (chunk == null) {
            throw new IOException("Missing entity origin for " + key + " in " + commit);
        }
        return chunk;
    }

    private static EntityChunkBlob canonical(
            EntityChunkBlob chunk,
            EntityChunkKey key,
            Set<UUID> identities,
            Map<UUID, Placement> selected) {
        return new EntityChunkBlob(chunk.entities().stream()
                .filter(entity -> !identities.contains(entity.id())
                        || Objects.equals(selected.get(entity.id()), new Placement(key, entity)))
                .toList());
    }

    private static IOException ambiguous(
            CommitId commit, UUID identity, List<Placement> candidates) {
        String chunks = candidates.stream()
                .map(candidate -> "(" + candidate.chunk.chunkX() + ","
                        + candidate.chunk.chunkZ() + ")")
                .sorted().distinct().toList().toString();
        return new IOException("Ambiguous entity UUID " + identity + " in commit "
                + commit + " across chunks " + chunks);
    }

    record Plan(
            Map<EntityChunkKey, EntityChunkBlob> target,
            Map<EntityChunkKey, EntityChunkBlob> before) {
        Plan {
            target = Map.copyOf(target);
            before = Map.copyOf(before);
        }
    }

    private record Snapshot(
            Map<EntityChunkKey, EntityChunkBlob> chunks,
            Map<UUID, List<Placement>> placements) { }
    private record Placement(EntityChunkKey chunk, EntityState state) { }
    private record SelectionKey(CommitId commit, UUID identity) { }
}
