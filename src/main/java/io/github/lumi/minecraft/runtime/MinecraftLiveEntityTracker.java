package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.MinecraftLiveEntityWorldAccess;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/** Bridges low-level entity lifecycle hooks into one live root action. */
public final class MinecraftLiveEntityTracker {
    private final LiveActionJournal journal;
    private final MinecraftLiveEntityWorldAccess world;
    private final BuilderMutationObserver builderMutations;
    private final Map<UUID, Map<UUID, Owned>> owned = new HashMap<>();

    public MinecraftLiveEntityTracker(
            LiveActionJournal journal,
            MinecraftLiveEntityWorldAccess world,
            BuilderMutationObserver builderMutations) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.world = Objects.requireNonNull(world, "world");
        this.builderMutations = Objects.requireNonNull(
                builderMutations, "builderMutations");
    }

    public void added(Entity entity) throws IOException {
        Optional<UUID> action = DirectLiveActionContext.current(journal);
        if (action.isEmpty()) {
            return;
        }
        Optional<EntityState> after = world.capture(entity);
        if (after.isPresent()) {
            EntityChunkKey chunk = world.chunk(after.orElseThrow());
            UUID effective = recordEntity(
                    action.orElseThrow(), entity.getUUID(), Optional.empty(), after);
            if (journal.hasEntityChange(effective, entity.getUUID())) own(effective, entity.getUUID(), Optional.empty());
            builderMutations.changed(Set.of(chunk));
        }
    }

    public Optional<Pending> begin(Entity entity) throws IOException {
        return begin(entity, false);
    }

    /** Seeds passengers as absent roots before a native batch can detach or remove them. */
    public Optional<Pending> beginGraphMember(Entity entity) throws IOException {
        return begin(entity, true);
    }

    private Optional<Pending> begin(Entity entity, boolean graphMember) throws IOException {
        Optional<UUID> action = DirectLiveActionContext.current(journal);
        if (action.isEmpty()) {
            return Optional.empty();
        }
        UUID actionId = action.orElseThrow();
        UUID entityId = entity.getUUID();
        Optional<EntityState> observedBefore = world.capture(entity);
        Optional<EntityChunkKey> chunk = observedBefore.isEmpty() ? Optional.empty()
                : Optional.of(world.chunk(observedBefore.orElseThrow()));
        if (observedBefore.isEmpty() && !(graphMember && entity.isPassenger())) {
            return Optional.empty();
        }
        Map<UUID, Owned> entities = owned.get(actionId);
        if (entities != null && entities.containsKey(entityId)) {
            world.references().discardUnowned(observedBefore);
            return Optional.of(new Pending(
                    actionId, entityId, entities.get(entityId).before(),
                    observedBefore, chunk));
        }
        own(actionId, entityId, observedBefore);
        return Optional.of(new Pending(
                actionId, entityId, observedBefore, observedBefore, chunk));
    }

    public boolean finish(Pending pending) throws IOException {
        Objects.requireNonNull(pending, "pending");
        Optional<EntityState> after = world.capture(pending.entity());
        Set<EntityChunkKey> chunks = new java.util.LinkedHashSet<>();
        pending.observedChunk().ifPresent(chunks::add);
        if (after.isPresent()) chunks.add(world.chunk(after.orElseThrow()));
        UUID effective = recordEntity(
                pending.action(), pending.entity(), pending.before(),
                after);
        boolean changed = !pending.observedBefore().equals(after);
        if (changed) {
            builderMutations.changed(Set.copyOf(chunks));
        }
        if (after.isPresent() && journal.hasEntityChange(effective, pending.entity())) {
            own(effective, pending.entity(), pending.before());
        } else {
            if (!effective.equals(pending.action())) disown(pending.action(), pending.entity());
            disown(effective, pending.entity());
        }
        return changed;
    }

    public boolean finalizeOwned(UUID action) throws IOException {
        Map<UUID, Owned> entities = owned.remove(action);
        if (entities == null) {
            return false;
        }
        try {
            for (var entry : entities.entrySet()) {
                Optional<EntityState> current = world.capture(entry.getKey());
                if (entry.getValue().endpoint() == Endpoint.BEFORE) {
                    journal.refreshEntityBefore(action, entry.getKey(), current);
                } else {
                    recordEntity(
                            action, entry.getKey(), entry.getValue().before(), current);
                }
            }
        } finally {
            entities.values().forEach(ownership -> world.references().release(ownership.before()));
            journal.release(action);
        }
        return true;
    }

    public void finalizeOwned() throws IOException {
        for (UUID action : java.util.Set.copyOf(owned.keySet())) {
            finalizeOwned(action);
        }
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
            owned.get(action).values().forEach(ownership -> world.references().release(ownership.before()));
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
            journal.retain(action);
            entities = new HashMap<>();
            owned.put(action, entities);
        }
        if (entities.putIfAbsent(entity, ownership) == null) world.references().retain(ownership.before());
        owned.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(action)
                        && entry.getValue().containsKey(entity))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(other -> disown(other, entity));
    }

    private void disown(UUID action, UUID entity) {
        Map<UUID, Owned> entities = owned.get(action);
        if (entities == null) return;
        Owned removed = entities.remove(entity);
        if (removed == null) return;
        world.references().release(removed.before());
        if (!entities.isEmpty()) return;
        owned.remove(action);
        journal.release(action);
    }

    private UUID recordEntity(
            UUID action,
            UUID entity,
            Optional<EntityState> before,
            Optional<EntityState> after) {
        UUID effective = journal.recordEntity(action, entity, before, after);
        if (!effective.equals(action)) {
            journal.mergeGroups(action, effective);
        }
        return effective;
    }

    public record Pending(
            UUID action,
            UUID entity,
            Optional<EntityState> before,
            Optional<EntityState> observedBefore,
            Optional<EntityChunkKey> observedChunk) {
        public Pending {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(observedBefore, "observedBefore");
            Objects.requireNonNull(observedChunk, "observedChunk");
        }
    }

    @FunctionalInterface
    public interface BuilderMutationObserver {
        void changed(Set<EntityChunkKey> chunks) throws IOException;
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
