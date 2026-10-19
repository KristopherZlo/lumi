package io.github.lumi.domain.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Portable action data; editor objects and native prepared states never enter storage. */
public record WorldActionRecord(Metadata metadata, Payload payload) {
    public WorldActionRecord {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(payload, "payload");
        if (metadata.checkpoint().isPresent() && (!payload.blocks().isEmpty() || !payload.entities().isEmpty() || !payload.biomes().isEmpty() || !payload.sections().isEmpty())) {
            throw new IllegalArgumentException("Checkpoint cannot contain a second action payload");
        }
    }

    public record Metadata(
            UUID id, UUID actor, long sequence, UUID group, long revision,
            boolean applied, boolean closed, boolean pending, Optional<WorldActionCheckpoint> checkpoint) {
        public Metadata {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(checkpoint, "checkpoint");
            if (sequence < 1 || revision < 0) throw new IllegalArgumentException("Invalid action order/revision");
        }
    }

    public record Payload(
            Map<BlockPosition, BlockDelta> blocks,
            Map<UUID, EntityDelta> entities, Map<BiomePosition, BiomeDelta> biomes, Map<SectionKey, SectionDelta> sections) {
        public Payload(Map<BlockPosition, BlockDelta> blocks, Map<UUID, EntityDelta> entities) { this(blocks, entities, Map.of(), Map.of()); }
        public Payload(Map<BlockPosition, BlockDelta> blocks, Map<UUID, EntityDelta> entities, Map<BiomePosition, BiomeDelta> biomes) { this(blocks, entities, biomes, Map.of()); }
        public Payload {
            blocks = orderedCopy(blocks);
            entities = orderedCopy(entities);
            biomes = orderedCopy(biomes);
            sections = orderedCopy(sections);
            entities.forEach((id, delta) -> {
                delta.before().ifPresent(value -> requireEntity(id, value));
                delta.after().ifPresent(value -> requireEntity(id, value));
            });
        }

        private static <K, V> Map<K, V> orderedCopy(Map<K, V> source) {
            var copy = new LinkedHashMap<K, V>();
            source.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
            return Collections.unmodifiableMap(copy);
        }

        private static void requireEntity(UUID id, EntityState value) {
            if (!id.equals(value.id())) throw new IllegalArgumentException("Action entity UUID mismatch");
        }
    }

    public record SectionDelta(SectionBlob before, SectionBlob after) {
        public SectionDelta {
            Objects.requireNonNull(before); Objects.requireNonNull(after);
            if (!before.dataVersion().equals(after.dataVersion()) || !before.actionBiomes().isEmpty() || !after.actionBiomes().isEmpty()) {
                throw new IllegalArgumentException("Action section endpoints must share their data version and contain only blocks");
            }
        }

        public SectionDelta followedBy(SectionDelta next) {
            var first = new java.util.ArrayList<>(before.blockStates());
            var last = new java.util.ArrayList<>(after.blockStates());
            var firstNbt = new java.util.HashMap<>(before.blockEntities());
            var lastNbt = new java.util.HashMap<>(after.blockEntities());
            for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
                if (next.changed(cell)) {
                    if (!changed(cell)) put(first, firstNbt, cell, next.before());
                    put(last, lastNbt, cell, next.after());
                }
            }
            return new SectionDelta(new SectionBlob(first, firstNbt, before.dataVersion()),
                    new SectionBlob(last, lastNbt, before.dataVersion()));
        }

        public SectionDelta followedByBlock(int cell, BlockSnapshot old, BlockSnapshot replacement) {
            var oldStates = new java.util.ArrayList<>(after.blockStates());
            var newStates = new java.util.ArrayList<>(after.blockStates());
            var oldNbt = new java.util.HashMap<>(after.blockEntities());
            var newNbt = new java.util.HashMap<>(after.blockEntities());
            oldStates.set(cell, old.blockState()); newStates.set(cell, replacement.blockState());
            oldNbt.remove(cell); newNbt.remove(cell);
            old.blockEntity().ifPresent(nbt -> oldNbt.put(cell, nbt));
            replacement.blockEntity().ifPresent(nbt -> newNbt.put(cell, nbt));
            return followedBy(new SectionDelta(new SectionBlob(oldStates, oldNbt, before.dataVersion()),
                    new SectionBlob(newStates, newNbt, before.dataVersion())));
        }

        public boolean changed(int cell) {
            return !before.blockStates().get(cell).equals(after.blockStates().get(cell))
                    || !Objects.equals(before.blockEntities().get(cell), after.blockEntities().get(cell));
        }

        /** Changes only this action's cells, after checking their observed endpoint. */
        public SectionBlob project(SectionBlob current, boolean undo) {
            before.dataVersion().requireSame(current.dataVersion());
            var expected = undo ? after : before;
            var target = undo ? before : after;
            var states = new java.util.ArrayList<>(current.blockStates());
            var nbt = new java.util.HashMap<>(current.blockEntities());
            for (int cell = 0; cell < SectionBlob.BLOCK_COUNT; cell++) {
                if (!changed(cell)) continue;
                if (!states.get(cell).equals(expected.blockStates().get(cell))
                        || !Objects.equals(nbt.get(cell), expected.blockEntities().get(cell))) {
                    throw new IllegalStateException("Action section cell changed at " + cell);
                }
                put(states, nbt, cell, target);
            }
            return new SectionBlob(states, nbt, current.dataVersion());
        }

        private static void put(java.util.List<String> states, Map<Integer, CanonicalNbt> nbt, int cell, SectionBlob source) {
            states.set(cell, source.blockStates().get(cell));
            var value = source.blockEntities().get(cell);
            if (value == null) nbt.remove(cell); else nbt.put(cell, value);
        }
    }

    public record BiomeDelta(String before, String after) {
        public BiomeDelta {
            if (Objects.requireNonNull(before).isBlank() || Objects.requireNonNull(after).isBlank()) {
                throw new IllegalArgumentException("Empty biome registry identity");
            }
        }
    }

    public record BlockDelta(BlockSnapshot before, BlockSnapshot after) {
        public BlockDelta {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }
    }

    public record EntityDelta(Optional<EntityState> before, Optional<EntityState> after) {
        public EntityDelta {
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(after, "after");
        }
    }
}
