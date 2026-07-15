package io.github.lumi.domain.service;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.SectionBlob;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Merges decoded leaves; a true conflict always selects the source value. */
public final class ThreeWayMerge {
    public SectionResult sections(
            SectionBlob base, SectionBlob current, SectionBlob source) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(source, "source");
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
                new SectionBlob(states, blockEntities), conflicts, changed);
    }

    public EntityResult entities(
            EntityChunkBlob base, EntityChunkBlob current, EntityChunkBlob source) {
        Map<UUID, EntityState> baseById = byId(base);
        Map<UUID, EntityState> currentById = byId(current);
        Map<UUID, EntityState> sourceById = byId(source);
        var ids = new TreeSet<UUID>();
        ids.addAll(baseById.keySet());
        ids.addAll(currentById.keySet());
        ids.addAll(sourceById.keySet());
        var merged = new TreeMap<UUID, EntityState>();
        int conflicts = 0;
        int changed = 0;
        for (UUID id : ids) {
            Optional<EntityState> currentValue = Optional.ofNullable(currentById.get(id));
            Choice<Optional<EntityState>> choice = choose(
                    Optional.ofNullable(baseById.get(id)), currentValue,
                    Optional.ofNullable(sourceById.get(id)));
            conflicts += choice.conflict ? 1 : 0;
            if (!choice.value.equals(currentValue)) {
                changed++;
            }
            choice.value.ifPresent(value -> merged.put(id, value));
        }
        return new EntityResult(
                new EntityChunkBlob(new ArrayList<>(merged.values())), conflicts, changed);
    }

    private static Cell cell(SectionBlob section, int index) {
        return new Cell(section.blockStates().get(index),
                Optional.ofNullable(section.blockEntities().get(index)));
    }

    private static Map<UUID, EntityState> byId(EntityChunkBlob chunk) {
        Objects.requireNonNull(chunk, "chunk");
        Map<UUID, EntityState> entities = new HashMap<>();
        chunk.entities().forEach(entity -> entities.put(entity.id(), entity));
        return entities;
    }

    private static <T> Choice<T> choose(T base, T current, T source) {
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

    private record Cell(String state, Optional<CanonicalNbt> blockEntity) { }

    private record Choice<T>(T value, boolean conflict) { }
}
