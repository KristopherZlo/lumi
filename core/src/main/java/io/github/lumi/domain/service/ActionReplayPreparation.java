package io.github.lumi.domain.service;

import io.github.lumi.domain.model.*;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.MerkleTreeEditor;
import io.github.lumi.storage.repository.OriginStore;
import io.github.lumi.storage.repository.WorldObjectGraph;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Projects portable live endpoints onto a frozen capture, preserving every unrelated cell. */
public final class ActionReplayPreparation {
    private final WorldObjectRepository objects;
    private final CommitRepository commits;
    private final OriginStore origins;
    private final WorldDataVersion version;

    public ActionReplayPreparation(WorldObjectRepository objects, CommitRepository commits,
            OriginStore origins, WorldDataVersion version) {
        this.objects = objects;
        this.commits = commits;
        this.origins = origins;
        this.version = version;
    }

    public CommitId prepare(CommitId captured, LiveActionJournal.Plan plan,
            Map<EntityState, EntityChunkKey> locations, CommitAuthor author) throws IOException {
        try (var publication = objects.beginPublication()) {
            Commit current = commits.read(captured);
            var leaves = new WorldObjectGraph(objects).scan(current.tree()).leaves();
            Map<HistoryKey, ObjectId> changes = new HashMap<>();
            for (var entry : plan.sections().entrySet()) {
                ObjectId id = leaf(leaves, entry.getKey()).orElseThrow(() -> new IOException("Missing action section origin"));
                SectionBlob currentSection = objects.readSection(id);
                var states = new ArrayList<>(currentSection.blockStates());
                var nbt = new HashMap<>(currentSection.blockEntities());
                boolean undo = plan.direction() == LiveActionJournal.Direction.UNDO;
                var expected = undo ? entry.getValue().after() : entry.getValue().before();
                var target = undo ? entry.getValue().before() : entry.getValue().after();
                for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
                    if (!entry.getValue().changed(cell)) continue;
                    if (!currentSection.blockStates().get(cell).equals(expected.blockStates().get(cell))
                            || !java.util.Objects.equals(currentSection.blockEntities().get(cell), expected.blockEntities().get(cell))) {
                        throw new IOException("Action section cell changed at " + entry.getKey() + ":" + cell);
                    }
                    states.set(cell, target.blockStates().get(cell));
                    var value = target.blockEntities().get(cell);
                    if (value == null) nbt.remove(cell); else nbt.put(cell, value);
                }
                changes.put(entry.getKey(), objects.write(new SectionBlob(states, nbt, currentSection.dataVersion())));
            }
            var sections = new HashMap<SectionKey, List<BlockPosition>>();
            plan.expected().keySet().forEach(position -> sections.computeIfAbsent(new SectionKey(
                    Math.floorDiv(position.x(), 16), Math.floorDiv(position.y(), 16), Math.floorDiv(position.z(), 16)),
                    ignored -> new ArrayList<>()).add(position));
            for (var entry : sections.entrySet()) {
                ObjectId id = leaf(leaves, entry.getKey()).orElseThrow(() -> new IOException("Missing action block origin"));
                SectionBlob before = objects.readSection(changes.getOrDefault(entry.getKey(), id));
                var blocks = new ArrayList<>(before.blockStates());
                var nbt = new HashMap<>(before.blockEntities());
                for (var position : entry.getValue()) {
                    int cell = (Math.floorMod(position.y(), 16) << 8) | (Math.floorMod(position.z(), 16) << 4)
                            | Math.floorMod(position.x(), 16);
                    var observed = new BlockSnapshot(blocks.get(cell), Optional.ofNullable(nbt.get(cell)));
                    if (!observed.equals(plan.expected().get(position))) throw new IOException("Action block changed at " + position);
                    var replacement = plan.replacement().get(position);
                    blocks.set(cell, replacement.blockState());
                    if (replacement.blockEntity().isPresent()) nbt.put(cell, replacement.blockEntity().orElseThrow());
                    else nbt.remove(cell);
                }
                changes.put(entry.getKey(), objects.write(new SectionBlob(blocks, nbt, before.dataVersion())));
            }
            int entityChunks = projectEntities(captured, plan, locations, leaves, changes);
            ObjectId tree = new MerkleTreeEditor(objects).update(Optional.of(current.tree()), changes);
            return commits.write(new Commit(tree, List.of(captured), author,
                    plan.direction() == LiveActionJournal.Direction.UNDO ? "Undo action" : "Redo action", Instant.now(),
                    current.workspaceId(), Optional.empty(), CommitKind.HIDDEN_RETURN,
                    new CommitStatistics(changes.size() - entityChunks, entityChunks, plan.expected().size(), plan.expectedEntities().size()),
                    current.playerSpawns()));
        }
    }

    private int projectEntities(CommitId captured, LiveActionJournal.Plan plan, Map<EntityState, EntityChunkKey> locations,
            Map<HistoryKey, ObjectId> leaves, Map<HistoryKey, ObjectId> changes) throws IOException {
        if (plan.expectedEntities().isEmpty()) return 0;
        var known = new HashMap<>(origins.entityEntries());
        leaves.forEach((key, id) -> { if (key instanceof EntityChunkKey chunk) known.put(chunk, id); });
        Map<UUID, EntityPlacement> observed;
        try (var reader = objects.beginReadSession()) {
            observed = new EntityRestorePlanner(objects, reader, commits, origins).locate(captured, plan.expectedEntities().keySet());
        }
        var edited = new HashMap<EntityChunkKey, Map<UUID, EntityState>>();
        for (var entry : known.entrySet()) {
            var chunk = objects.readEntities(entry.getValue());
            if (chunk.entities().stream().noneMatch(entity -> plan.expectedEntities().containsKey(entity.id()))) continue;
            var retained = new HashMap<UUID, EntityState>();
            for (var entity : chunk.entities()) {
                if (!plan.expectedEntities().containsKey(entity.id())) retained.put(entity.id(), entity);
            }
            edited.put(entry.getKey(), retained);
        }
        for (var entry : plan.expectedEntities().entrySet()) {
            if (!entry.getValue().equals(Optional.ofNullable(observed.get(entry.getKey())).map(EntityPlacement::state))) {
                throw new IOException("Action entity changed: " + entry.getKey());
            }
            var replacement = plan.replacementEntities().get(entry.getKey());
            if (replacement.isEmpty()) continue;
            var entity = replacement.orElseThrow();
            var chunk = locations.get(entity);
            if (chunk == null) throw new IOException("Missing prepared action entity location: " + entity.id());
            var contents = edited.get(chunk);
            if (contents == null) {
                contents = new HashMap<>();
                ObjectId id = known.get(chunk);
                if (id != null) for (var value : objects.readEntities(id).entities()) contents.put(value.id(), value);
                edited.put(chunk, contents);
            }
            contents.put(entity.id(), entity);
        }
        for (var entry : edited.entrySet()) changes.put(entry.getKey(), objects.write(
                new EntityChunkBlob(List.copyOf(entry.getValue().values()), version)));
        return edited.size();
    }

    private Optional<ObjectId> leaf(Map<HistoryKey, ObjectId> leaves, HistoryKey key) throws IOException {
        ObjectId id = leaves.get(key);
        return id == null ? origins.read(key) : Optional.of(id);
    }

    /** Adds only selected biome cells to a hidden apply endpoint; normal snapshots remain biome-free. */
    public CommitId withBiomes(CommitId base, Map<BiomePosition, WorldActionRecord.BiomeDelta> biomes,
            boolean after, CommitAuthor author) throws IOException {
        if (biomes.isEmpty()) return base;
        try (var publication = objects.beginPublication()) {
            var current = commits.read(base);
            var leaves = new WorldObjectGraph(objects).scan(current.tree()).leaves();
            var selected = new HashMap<SectionKey, Map<Integer, String>>();
            biomes.forEach((position, delta) -> selected.computeIfAbsent(position.section(), ignored -> new HashMap<>())
                    .put(position.localIndex(), after ? delta.after() : delta.before()));
            Map<HistoryKey, ObjectId> changes = new HashMap<>();
            for (var entry : selected.entrySet()) {
                var id = leaf(leaves, entry.getKey()).orElseThrow(() -> new IOException("Missing biome action section origin"));
                changes.put(entry.getKey(), objects.write(objects.readSection(id).withActionBiomes(entry.getValue())));
            }
            var tree = new MerkleTreeEditor(objects).update(Optional.of(current.tree()), changes);
            return commits.write(new Commit(tree, List.of(base), author, "Biome action endpoint", Instant.now(),
                    current.workspaceId(), Optional.empty(), CommitKind.HIDDEN_RETURN,
                    new CommitStatistics(changes.size(), 0, 0, 0), current.playerSpawns()));
        }
    }
}
