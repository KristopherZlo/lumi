package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.MinecraftLiveEntityWorldAccess;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/** Bridges low-level entity lifecycle hooks into one live root action. */
public final class MinecraftLiveEntityTracker {
    private final LiveActionJournal journal;
    private final MinecraftLiveEntityWorldAccess world;
    private final Map<UUID, Map<UUID, Owned>> owned = new HashMap<>();

    public MinecraftLiveEntityTracker(
            LiveActionJournal journal, MinecraftLiveEntityWorldAccess world) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.world = Objects.requireNonNull(world, "world");
    }

    public void added(Entity entity) throws IOException {
        Optional<UUID> action = DirectLiveActionContext.current(journal);
        if (action.isEmpty()) {
            return;
        }
        Optional<EntityState> after = world.capture(entity);
        if (after.isPresent()) {
            journal.recordEntity(
                    action.orElseThrow(), entity.getUUID(), Optional.empty(), after);
            own(action.orElseThrow(), entity.getUUID(), Optional.empty());
        }
    }

    public Optional<Pending> begin(Entity entity) throws IOException {
        Optional<UUID> action = DirectLiveActionContext.current(journal);
        if (action.isEmpty()) {
            return Optional.empty();
        }
        UUID actionId = action.orElseThrow();
        UUID entityId = entity.getUUID();
        Map<UUID, Owned> entities = owned.get(actionId);
        if (entities != null && entities.containsKey(entityId)) {
            return Optional.of(new Pending(
                    actionId, entityId, entities.get(entityId).before()));
        }
        Optional<EntityState> before = world.capture(entity);
        if (before.isEmpty()) {
            return Optional.empty();
        }
        own(actionId, entityId, before);
        return Optional.of(new Pending(actionId, entityId, before));
    }

    public void finish(Pending pending) throws IOException {
        Objects.requireNonNull(pending, "pending");
        Optional<EntityState> after = world.read(pending.entity());
        journal.recordEntity(
                pending.action(), pending.entity(), pending.before(),
                after);
        if (after.isPresent()) {
            own(pending.action(), pending.entity(), pending.before());
        } else {
            disown(pending.action(), pending.entity());
        }
    }

    public boolean finalizeOwned(UUID action) throws IOException {
        Map<UUID, Owned> entities = owned.remove(action);
        if (entities == null) {
            return false;
        }
        try {
            for (var entry : entities.entrySet()) {
                Optional<EntityState> current = world.read(entry.getKey());
                if (entry.getValue().endpoint() == Endpoint.BEFORE) {
                    journal.refreshEntityBefore(action, entry.getKey(), current);
                } else {
                    journal.recordEntity(
                            action, entry.getKey(), entry.getValue().before(), current);
                }
            }
        } finally {
            journal.release(action);
        }
        return true;
    }

    public void trackApplied(
            UUID action,
            LiveActionJournal.Direction direction,
            Map<UUID, Optional<EntityState>> expected,
            Map<UUID, Optional<EntityState>> replacement) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(replacement, "replacement").forEach((entity, state) -> {
            if (state.isPresent()) {
                Endpoint endpoint = direction == LiveActionJournal.Direction.UNDO
                        ? Endpoint.BEFORE : Endpoint.AFTER;
                Optional<EntityState> origin = endpoint == Endpoint.BEFORE
                        ? state : Objects.requireNonNull(expected.get(entity));
                own(action, entity, new Owned(endpoint, origin));
            }
        });
    }

    public boolean owns(UUID action, UUID entity) {
        Map<UUID, Owned> entities = owned.get(Objects.requireNonNull(action, "action"));
        return entities != null && entities.containsKey(
                Objects.requireNonNull(entity, "entity"));
    }

    public void clear() {
        for (UUID action : owned.keySet()) {
            journal.release(action);
        }
        owned.clear();
    }

    private void own(
            UUID action, UUID entity, Optional<EntityState> before) {
        own(action, entity, new Owned(Endpoint.AFTER, before));
    }

    private void own(UUID action, UUID entity, Owned ownership) {
        Map<UUID, Owned> entities = owned.get(action);
        if (entities == null) {
            entities = new HashMap<>();
            owned.put(action, entities);
            journal.retain(action);
        }
        entities.putIfAbsent(entity, ownership);
    }

    private void disown(UUID action, UUID entity) {
        Map<UUID, Owned> entities = owned.get(action);
        if (entities == null || entities.remove(entity) == null || !entities.isEmpty()) {
            return;
        }
        owned.remove(action);
        journal.release(action);
    }

    public record Pending(
            UUID action, UUID entity, Optional<EntityState> before) {
        public Pending {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(before, "before");
        }
    }

    private enum Endpoint {
        BEFORE,
        AFTER
    }

    private record Owned(Endpoint endpoint, Optional<EntityState> before) {
        private Owned {
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(before, "before");
        }
    }
}
