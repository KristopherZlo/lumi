package io.github.lumi.domain.model;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Compact changed-cell and entity identities, without retaining snapshot contents. */
public final class WorldActionScope {
    private static final WorldActionScope EMPTY = new WorldActionScope(Map.of(), Set.of());
    private final Map<SectionKey, BitSet> blocks;
    private final Set<UUID> entities;
    private final long bytes;

    private WorldActionScope(Map<SectionKey, BitSet> blocks, Set<UUID> entities) {
        this.blocks = Map.copyOf(blocks);
        this.entities = Set.copyOf(entities);
        bytes = blocks.values().stream().mapToLong(bits -> 96L + bits.size() / 8).sum()
                + 64L * entities.size();
    }

    public static WorldActionScope empty() { return EMPTY; }

    public static WorldActionScope between(
            Map<SectionKey, SectionBlob> before, Map<SectionKey, SectionBlob> after,
            Map<EntityChunkKey, EntityChunkBlob> beforeEntities,
            Map<EntityChunkKey, EntityChunkBlob> afterEntities) {
        if (!before.keySet().equals(after.keySet())) {
            throw new IllegalArgumentException("Action endpoints must cover identical section keys");
        }
        Map<SectionKey, BitSet> blocks = new HashMap<>();
        after.forEach((key, target) -> {
            SectionBlob source = before.get(key);
            source.dataVersion().requireSame(target.dataVersion());
            BitSet changed = new BitSet();
            for (int index = 0; index < SectionBlob.BLOCK_COUNT; index++) {
                if (!source.blockStates().get(index).equals(target.blockStates().get(index))
                        || !Objects.equals(source.blockEntities().get(index), target.blockEntities().get(index))) {
                    changed.set(index);
                }
            }
            if (!changed.isEmpty()) blocks.put(key, changed);
        });
        var sourceEntities = EntityPlacement.index(beforeEntities);
        var targetEntities = EntityPlacement.index(afterEntities);
        Set<UUID> changedEntities = new HashSet<>(sourceEntities.keySet());
        changedEntities.addAll(targetEntities.keySet());
        changedEntities.removeIf(id -> Objects.equals(sourceEntities.get(id), targetEntities.get(id)));
        return new WorldActionScope(blocks, changedEntities);
    }

    public boolean intersects(Set<BlockPosition> positions, Set<UUID> entityIds) {
        for (BlockPosition position : positions) {
            BitSet cells = blocks.get(new SectionKey(position.x() >> 4, position.y() >> 4, position.z() >> 4));
            int index = (position.y() & 15) << 8 | (position.z() & 15) << 4 | (position.x() & 15);
            if (cells != null && cells.get(index)) return true;
        }
        return entityIds.stream().anyMatch(entities::contains);
    }

    public boolean intersects(WorldActionScope other) {
        Objects.requireNonNull(other, "other");
        for (var entry : blocks.entrySet()) {
            BitSet cells = other.blocks.get(entry.getKey());
            if (cells != null && cells.intersects(entry.getValue())) return true;
        }
        return entities.stream().anyMatch(other.entities::contains);
    }

    public long estimatedBytes() { return bytes; }

    @Override public boolean equals(Object other) {
        return other instanceof WorldActionScope scope
                && blocks.equals(scope.blocks) && entities.equals(scope.entities);
    }

    @Override public int hashCode() { return Objects.hash(blocks, entities); }
}
