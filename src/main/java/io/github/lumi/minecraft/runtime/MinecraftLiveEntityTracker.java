package io.github.lumi.minecraft.runtime;

import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.service.LiveActionJournal;
import io.github.lumi.minecraft.world.MinecraftLiveEntityWorldAccess;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.entity.Entity;

/** Bridges low-level entity lifecycle hooks into one live root action. */
public final class MinecraftLiveEntityTracker {
    private final LiveActionJournal journal;
    private final MinecraftLiveEntityWorldAccess world;

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
        journal.recordEntity(
                pending.action(), pending.entity(), Optional.of(pending.before()),
                world.read(pending.entity()));
    }

    public record Pending(UUID action, UUID entity, EntityState before) {
        public Pending {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(before, "before");
        }
    }
}
