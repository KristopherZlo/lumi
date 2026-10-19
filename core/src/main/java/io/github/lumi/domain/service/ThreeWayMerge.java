package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.EntityPlacement;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.WorldDataVersion;
import io.github.lumi.domain.model.PlayerSpawn;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Merges decoded leaves, with strict expected-state checks for action replay. */
public final class ThreeWayMerge {
    private final boolean requireExpected;

    public ThreeWayMerge() { this(false); }

    private ThreeWayMerge(boolean requireExpected) { this.requireExpected = requireExpected; }

    public static ThreeWayMerge forActionReplay() { return new ThreeWayMerge(true); }

    public SectionResult sections(
            SectionBlob base, SectionBlob current, SectionBlob source) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(source, "source");
        current.dataVersion().requireSame(base.dataVersion());
        current.dataVersion().requireSame(source.dataVersion());
        var states = new ArrayList<>(current.blockStates());
        var blockEntities = new HashMap<>(current.blockEntities());
        int conflicts = 0;
        long changed = 0;
        for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
            Cell baseCell = cell(base, index);
            Cell currentCell = cell(current, index);
            Cell sourceCell = cell(source, index);
            Choice<Cell> choice = choose(baseCell, currentCell, sourceCell);
            conflicts += choice.conflict ? 1 : 0;
            if (!choice.value.equals(currentCell)) {
                changed++;
                states.set(index, choice.value.state);
                blockEntities.remove(index);
                if (choice.value.blockEntity.isPresent()) {
                    blockEntities.put(index, choice.value.blockEntity.orElseThrow());
                }
            }
        }
        return new SectionResult(
                new SectionBlob(states, blockEntities, current.dataVersion()), conflicts, changed);
    }

    public EntityResult entities(
            EntityChunkBlob base, EntityChunkBlob current, EntityChunkBlob source) {
        EntityChunkKey key = new EntityChunkKey(0, 0);
        EntityWorldResult result = entityChunks(
                Map.of(key, base), Map.of(key, current), Map.of(key, source));
        return new EntityResult(
                result.value.get(key), result.conflicts, result.changedEntities);
    }

    public EntityWorldResult entityChunks(
            Map<EntityChunkKey, EntityChunkBlob> base,
            Map<EntityChunkKey, EntityChunkBlob> current,
            Map<EntityChunkKey, EntityChunkBlob> source) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(source, "source");
        WorldDataVersion version = null;
        for (var world : java.util.List.of(base, current, source)) {
            for (var chunk : world.values()) {
                if (version == null) version = chunk.dataVersion();
                else version.requireSame(chunk.dataVersion());
            }
        }
        WorldDataVersion mergedVersion = version == null ? WorldDataVersion.LEGACY_12111 : version;
        var keys = new HashSet<EntityChunkKey>();
        keys.addAll(base.keySet());
        keys.addAll(current.keySet());
        keys.addAll(source.keySet());
        Map<UUID, EntityPlacement> baseById = EntityPlacement.index(base);
        Map<UUID, EntityPlacement> currentById = EntityPlacement.index(current);
        Map<UUID, EntityPlacement> sourceById = EntityPlacement.index(source);
        var ids = new TreeSet<UUID>();
        ids.addAll(baseById.keySet());
        ids.addAll(currentById.keySet());
        ids.addAll(sourceById.keySet());
        Map<EntityChunkKey, TreeMap<UUID, EntityState>> merged = new HashMap<>();
        for (EntityChunkKey key : keys) {
            TreeMap<UUID, EntityState> entities = new TreeMap<>();
            current.getOrDefault(key, new EntityChunkBlob(java.util.List.of()))
                    .entities().forEach(entity -> entities.put(entity.id(), entity));
            merged.put(key, entities);
        }
        int conflicts = 0;
        int changed = 0;
        for (UUID id : ids) {
            Optional<EntityPlacement> currentValue = Optional.ofNullable(currentById.get(id));
            Choice<Optional<EntityPlacement>> choice = choose(
                    Optional.ofNullable(baseById.get(id)), currentValue,
                    Optional.ofNullable(sourceById.get(id)));
            conflicts += choice.conflict ? 1 : 0;
            if (!choice.value.equals(currentValue)) {
                changed++;
                currentValue.ifPresent(value -> merged.get(value.chunk()).remove(id));
                choice.value.ifPresent(value ->
                        merged.get(value.chunk()).put(id, value.state()));
            }
        }
        Map<EntityChunkKey, EntityChunkBlob> result = new HashMap<>();
        merged.forEach((key, entities) -> result.put(
                key, new EntityChunkBlob(new ArrayList<>(entities.values()), mergedVersion)));
        return new EntityWorldResult(result, conflicts, changed);
    }

    public PlayerSpawnResult playerSpawns(
            Map<UUID, PlayerSpawn> base,
            Map<UUID, PlayerSpawn> current,
            Map<UUID, PlayerSpawn> source) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(source, "source");
        var ids = new TreeSet<UUID>();
        ids.addAll(base.keySet());
        ids.addAll(current.keySet());
        ids.addAll(source.keySet());
        Map<UUID, PlayerSpawn> result = new TreeMap<>(current);
        int conflicts = 0;
        for (UUID id : ids) {
            Choice<Optional<PlayerSpawn>> choice = choose(
                    Optional.ofNullable(base.get(id)),
                    Optional.ofNullable(current.get(id)),
                    Optional.ofNullable(source.get(id)));
            conflicts += choice.conflict ? 1 : 0;
            if (choice.value.isPresent()) {
                result.put(id, choice.value.orElseThrow());
            } else {
                result.remove(id);
            }
        }
        return new PlayerSpawnResult(result, conflicts);
    }

    private static Cell cell(SectionBlob section, int index) {
        return new Cell(section.blockStates().get(index),
                Optional.ofNullable(section.blockEntities().get(index)));
    }

    private <T> Choice<T> choose(T base, T current, T source) {
        if (requireExpected && !Objects.equals(source, base)
                && !Objects.equals(current, base)) {
            throw new IllegalStateException("World action conflicts with visible state");
        }
        if (Objects.equals(current, source)) {
            return new Choice<>(current, false);
        }
        if (Objects.equals(current, base)) {
            return new Choice<>(source, false);
        }
        if (Objects.equals(source, base)) {
            return new Choice<>(current, false);
        }
        return new Choice<>(source, true);
    }

    public record SectionResult(SectionBlob value, int conflicts, long changedBlocks) { }

    public record EntityResult(EntityChunkBlob value, int conflicts, int changedEntities) { }

    public record EntityWorldResult(
            Map<EntityChunkKey, EntityChunkBlob> value,
            int conflicts,
            int changedEntities) {
        public EntityWorldResult {
            value = Map.copyOf(value);
        }
    }

    public record PlayerSpawnResult(Map<UUID, PlayerSpawn> value, int conflicts) {
        public PlayerSpawnResult {
            value = Map.copyOf(value);
        }
    }

    private record Cell(String state, Optional<CanonicalNbt> blockEntity) { }

    private record Choice<T>(T value, boolean conflict) { }
}
