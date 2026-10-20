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

    /** Records the actual prepared result, including clipped Restore plans, without moving saved refs. */
    public WorldActionCheckpoint checkpoint(BranchRef before, PreparedRestore restore) throws IOException {
        try (var publication = objects.beginPublication()) {
            var captured = commits.read(before.commit());
            Map<HistoryKey, ObjectId> changes = new HashMap<>();
            for (var entry : restore.sections().entrySet()) changes.put(entry.getKey(), objects.write(entry.getValue()));
            for (var entry : restore.entities().entrySet()) changes.put(entry.getKey(), objects.write(entry.getValue()));
            var tree = new MerkleTreeEditor(objects).update(Optional.of(captured.tree()), changes);
            var after = commits.write(new Commit(tree, List.of(before.commit()), captured.author(),
                    "World action endpoint", Instant.now(), captured.workspaceId(), Optional.empty(), CommitKind.HIDDEN_RETURN,
                    new CommitStatistics(restore.sections().size(), restore.entities().size(), 0, 0),
                    restore.restorePlayerSpawns() ? restore.playerSpawns() : captured.playerSpawns()));
            var scope = WorldActionScope.between(restore.returnSections(), restore.sections(), restore.returnEntities(), restore.entities());
            return new WorldActionCheckpoint(restore.expectedRef().name(), before.commit(), after, before, scope);
        }
    }

    public CommitId replayCheckpoint(CommitId captured, WorldActionCheckpoint checkpoint, boolean undo, CommitAuthor author) throws IOException {
        try (var publication = objects.beginPublication()) {
            var workspace = commits.read(captured).workspaceId();
            var expected = inWorkspace(undo ? checkpoint.after() : checkpoint.before(), workspace);
            var target = inWorkspace(undo ? checkpoint.before() : checkpoint.after(), workspace);
            return new MergeService(objects, commits, origins, new MerkleTreeEditor(objects)).prepareReplay(
                    new MergeService.ReplayRequest(captured, expected, target, author,
                            undo ? "Undo action" : "Redo action", Instant.now(), workspace)).commit();
        }
    }

    private CommitId inWorkspace(CommitId id, UUID workspace) throws IOException {
        var value = commits.read(id);
        if (value.workspaceId().equals(workspace)) return id;
        return commits.write(new Commit(value.tree(), value.parents(), value.author(), value.message(), value.timestamp(),
                workspace, Optional.empty(), CommitKind.HIDDEN_RETURN, value.statistics(), value.playerSpawns()));
    }

    public CommitId prepare(CommitId captured, LiveActionJournal.Plan plan,
            Map<EntityState, EntityChunkKey> locations, CommitAuthor author) throws IOException {
        try (var publication = objects.beginPublication()) {
            Commit current = commits.read(captured);
            var leaves = new WorldObjectGraph(objects).scan(current.tree()).leaves();
            Map<HistoryKey, ObjectId> changes = new HashMap<>();
            var positions = blockPositions(plan);
            for (var key : sectionKeys(plan)) {
                ObjectId id = leaf(leaves, key).orElseThrow(() -> new IOException("Missing action section origin"));
                var target = projectSection(key, objects.readSection(id), plan, positions.getOrDefault(key, List.of()));
                changes.put(key, objects.write(target));
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

    /** Projects a small block action into section endpoints without taking a world-wide Save. */
    public LiveActionJournal.Plan sectionPlan(Map<SectionKey, SectionBlob> current, LiveActionJournal.Plan plan) throws IOException {
        if (current.isEmpty() || !current.keySet().equals(sectionKeys(plan)) || !plan.expectedEntities().isEmpty()
                || !plan.biomes().isEmpty() || plan.checkpoint().isPresent()) throw new IllegalArgumentException("Not a block replay");
        var target = projectSections(current, plan);
        var deltas = new HashMap<SectionKey, WorldActionRecord.SectionDelta>();
        boolean undo = plan.direction() == LiveActionJournal.Direction.UNDO;
        current.forEach((key, value) -> deltas.put(key, new WorldActionRecord.SectionDelta(
                undo ? target.get(key) : value, undo ? value : target.get(key))));
        return new LiveActionJournal.Plan(plan.player(), plan.actionIds(), plan.direction(), Map.of(), Map.of(),
                Map.of(), Map.of(), Optional.empty(), Map.of(), deltas);
    }

    private static java.util.Set<SectionKey> sectionKeys(LiveActionJournal.Plan plan) {
        var keys = new java.util.HashSet<>(plan.sections().keySet());
        plan.expected().keySet().forEach(position -> keys.add(new SectionKey(
                Math.floorDiv(position.x(), 16), Math.floorDiv(position.y(), 16), Math.floorDiv(position.z(), 16))));
        return keys;
    }

    /** Seeds a frozen capture with owned sections and all recorded entity endpoints. */
    public static java.util.Set<HistoryKey> captureKeys(LiveActionJournal.Plan plan, Map<EntityState, EntityChunkKey> locations) throws IOException {
        var keys = new java.util.HashSet<HistoryKey>(sectionKeys(plan));
        plan.biomes().keySet().forEach(position -> keys.add(position.section()));
        for (var state : java.util.stream.Stream.concat(plan.expectedEntities().values().stream(), plan.replacementEntities().values().stream())
                .flatMap(Optional::stream).toList()) {
            var key = locations.get(state);
            if (key == null) throw new IOException("Missing prepared action entity location: " + state.id());
            keys.add(key);
        }
        return java.util.Set.copyOf(keys);
    }

    /** Metadata discovery uses the same identity closure as Restore, before live capture expands. */
    public Map<EntityChunkKey, java.util.Set<UUID>> entityCaptureScope(CommitId before,
            java.util.Set<EntityChunkKey> keys, java.util.Set<UUID> identities) throws IOException {
        try (var reader = objects.beginReadSession()) {
            return new EntityRestorePlanner(objects, reader, commits, origins).captureScope(before, keys, identities);
        }
    }

    private static Map<SectionKey, List<BlockPosition>> blockPositions(LiveActionJournal.Plan plan) {
        var positions = new HashMap<SectionKey, List<BlockPosition>>();
        plan.expected().keySet().forEach(position -> positions.computeIfAbsent(new SectionKey(Math.floorDiv(position.x(), 16),
                Math.floorDiv(position.y(), 16), Math.floorDiv(position.z(), 16)), ignored -> new ArrayList<>()).add(position));
        return positions;
    }

    private static Map<SectionKey, SectionBlob> projectSections(Map<SectionKey, SectionBlob> current, LiveActionJournal.Plan plan) throws IOException {
        var target = new HashMap<SectionKey, SectionBlob>();
        var positions = blockPositions(plan);
        for (var entry : current.entrySet()) target.put(entry.getKey(), projectSection(
                entry.getKey(), entry.getValue(), plan, positions.getOrDefault(entry.getKey(), List.of())));
        return target;
    }

    private static SectionBlob projectSection(SectionKey key, SectionBlob current, LiveActionJournal.Plan plan,
            List<BlockPosition> positions) throws IOException {
        var delta = plan.sections().get(key);
        var before = delta == null ? current : projectSection(key, current, delta, plan.direction());
        if (positions.isEmpty()) return before;
        var blocks = new ArrayList<>(before.blockStates());
        var nbt = new HashMap<>(before.blockEntities());
        for (var position : positions) {
            int cell = (Math.floorMod(position.y(), 16) << 8) | (Math.floorMod(position.z(), 16) << 4) | Math.floorMod(position.x(), 16);
            if (!new BlockSnapshot(blocks.get(cell), Optional.ofNullable(nbt.get(cell))).equals(plan.expected().get(position)))
                throw new IOException("Action block changed at " + position);
            var replacement = plan.replacement().get(position);
            blocks.set(cell, replacement.blockState());
            if (replacement.blockEntity().isPresent()) nbt.put(cell, replacement.blockEntity().orElseThrow()); else nbt.remove(cell);
        }
        return new SectionBlob(blocks, nbt, before.dataVersion());
    }

    /** Persists only the frozen affected sections; both endpoints remain ordinary recovery commits. */
    public SectionReplay prepareSections(BranchRef expected, Map<SectionKey, SectionBlob> current,
            Map<UUID, PlayerSpawn> spawns, LiveActionJournal.Plan plan, CommitAuthor author) throws IOException {
        if (current.isEmpty() || !current.keySet().equals(sectionKeys(plan))
                || !plan.expectedEntities().isEmpty() || !plan.biomes().isEmpty() || plan.checkpoint().isPresent()) {
            throw new IllegalArgumentException("Section replay contains other action data");
        }
        try (var publication = objects.beginPublication()) {
            var target = projectSections(current, plan);
            var workspace = commits.read(expected.commit()).workspaceId();
            CommitId before = writeSections(current, expected.commit(), workspace, spawns, author);
            CommitId after = writeSections(target, before, workspace, spawns, author);
            return new SectionReplay(before, new PreparedRestore(expected, after,
                    target, Map.of(), current, Map.of()));
        }
    }

    private CommitId writeSections(Map<SectionKey, SectionBlob> sections, CommitId parent, UUID workspace,
            Map<UUID, PlayerSpawn> spawns, CommitAuthor author) throws IOException {
        var changes = new HashMap<HistoryKey, ObjectId>();
        for (var entry : sections.entrySet()) changes.put(entry.getKey(), objects.write(entry.getValue()));
        var tree = new MerkleTreeEditor(objects).update(Optional.empty(), changes);
        return commits.write(new Commit(tree, List.of(parent), author, "World action endpoint", Instant.now(),
                workspace, Optional.empty(), CommitKind.HIDDEN_RETURN,
                new CommitStatistics(sections.size(), 0, (long) sections.size() * SectionBlob.BLOCK_COUNT, 0), spawns));
    }

    private static SectionBlob projectSection(SectionKey key, SectionBlob current,
            WorldActionRecord.SectionDelta delta, LiveActionJournal.Direction direction) throws IOException {
        if (!current.dataVersion().equals(delta.before().dataVersion())) throw new IOException("Action data version changed");
        var states = new ArrayList<>(current.blockStates());
        var nbt = new HashMap<>(current.blockEntities());
        var expected = direction == LiveActionJournal.Direction.UNDO ? delta.after() : delta.before();
        var target = direction == LiveActionJournal.Direction.UNDO ? delta.before() : delta.after();
        for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
            if (!delta.changed(cell)) continue;
            if (!states.get(cell).equals(expected.blockStates().get(cell))
                    || !java.util.Objects.equals(nbt.get(cell), expected.blockEntities().get(cell))) {
                throw new IOException("Action section cell changed at " + key + ":" + cell);
            }
            states.set(cell, target.blockStates().get(cell));
            var value = target.blockEntities().get(cell);
            if (value == null) nbt.remove(cell); else nbt.put(cell, value);
        }
        return new SectionBlob(states, nbt, current.dataVersion());
    }

    public record SectionReplay(CommitId returnPoint, PreparedRestore restore) { }

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
