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
        if (metadata.checkpoint().isPresent() && (!payload.blocks().isEmpty() || !payload.entities().isEmpty())) {
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
            Map<UUID, EntityDelta> entities) {
        public Payload {
            blocks = orderedCopy(blocks);
            entities = orderedCopy(entities);
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
