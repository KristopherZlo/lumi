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
        return world.capture(entity).map(before ->
                new Pending(action.orElseThrow(), entity.getUUID(), before));
    }

    public void finish(Pending pending) throws IOException {
        Objects.requireNonNull(pending, "pending");
        Optional<EntityState> after = world.read(pending.entity());
        journal.recordEntity(
                pending.action(), pending.entity(), Optional.of(pending.before()),
                after);
        if (after.isPresent()) {
            own(pending.action(), pending.entity(), Optional.of(pending.before()));
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

    public record Pending(UUID action, UUID entity, EntityState before) {
        public Pending {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(before, "before");
        }
    }
}
