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
    private final Map<UUID, Map<UUID, Optional<EntityState>>> owned = new HashMap<>();

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
        Map<UUID, Optional<EntityState>> entities = owned.get(actionId);
        if (entities != null && entities.containsKey(entityId)) {
            return Optional.of(new Pending(
                    actionId, entityId, entities.get(entityId)));
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

    public void finalizeOwned(UUID action) throws IOException {
        Map<UUID, Optional<EntityState>> entities = owned.remove(action);
        if (entities == null) {
            return;
        }
        try {
            for (var entry : entities.entrySet()) {
                journal.recordEntity(
                        action, entry.getKey(), entry.getValue(), world.read(entry.getKey()));
            }
        } finally {
            journal.release(action);
        }
    }

    public void trackRedone(
            UUID action,
            Map<UUID, Optional<EntityState>> before,
            Map<UUID, Optional<EntityState>> after) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after").forEach((entity, state) -> {
            if (state.isPresent()) {
                own(action, entity, Objects.requireNonNull(before.get(entity)));
            }
        });
    }

    public void clear() {
        for (UUID action : owned.keySet()) {
            journal.release(action);
        }
        owned.clear();
    }

    private void own(
            UUID action, UUID entity, Optional<EntityState> before) {
        Map<UUID, Optional<EntityState>> entities = owned.get(action);
        if (entities == null) {
            entities = new HashMap<>();
            owned.put(action, entities);
            journal.retain(action);
        }
        entities.putIfAbsent(entity, before);
    }

    private void disown(UUID action, UUID entity) {
        Map<UUID, Optional<EntityState>> entities = owned.get(action);
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
}
