package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

/** Idempotently replaces one loaded durable entity graph by persistent identity. */
final class MinecraftEntityRestorer {
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final MinecraftEntityChunkCapture capture;
    private final ChunkEntityLookup lookup;

    MinecraftEntityRestorer(
            ServerLevel level,
            DimensionFreezeState freeze,
            MinecraftEntityChunkCapture capture,
            ChunkEntityLookup lookup) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        this.capture = Objects.requireNonNull(capture, "capture");
        this.lookup = Objects.requireNonNull(lookup, "lookup");
    }

    Optional<Entity> findDurableRoot(UUID id) {
        return find(id).filter(MinecraftEntityChunkCapture::isDurableRoot);
    }

    void remove(UUID id) throws IOException {
        Optional<Entity> current = find(id);
        if (current.isPresent()) {
            removeGraph(current.orElseThrow());
        }
    }

    void remove(EntityChunkKey key, UUID id) throws IOException {
        Optional<Entity> current = find(
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(id, "id"));
        if (current.isPresent()) {
            removeGraph(current.orElseThrow());
        }
    }

    void restore(EntityChunkKey key, DecodedEntity decoded) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(decoded, "decoded");
        Optional<Entity> existing = find(key, decoded.id());
        if (existing.isPresent()) {
            Entity current = existing.orElseThrow();
            if (matches(current, key, decoded.state())) {
                return;
            }
        }

        Entity replacement = EntityType.loadEntityRecursive(
                decoded.type(), decoded.nbt().copy(), level,
                EntitySpawnReason.LOAD, EntityProcessor.NOP);
        if (replacement == null || !replacement.getUUID().equals(decoded.id())
                || replacement.chunkPosition().x != key.chunkX()
                || replacement.chunkPosition().z != key.chunkZ()) {
            throw new IOException("Restored entity does not match " + key + ": " + decoded.id());
        }
        List<Entity> replacementGraph = replacement.getSelfAndPassengers().toList();
        var replacementIds = new LinkedHashSet<UUID>();
        replacementGraph.forEach(entity -> replacementIds.add(entity.getUUID()));
        if (replacementIds.size() != replacementGraph.size()) {
            throw new IOException(
                    "Restored entity graph contains duplicate UUIDs: " + decoded.id());
        }
        for (UUID id : replacementIds) {
            Optional<Entity> current = find(key, id);
            if (current.isPresent()) {
                removeGraph(current.orElseThrow());
            }
        }
        List<UUID> conflicts = replacementIds.stream()
                .filter(lookup::isKnown)
                .toList();
        if (!conflicts.isEmpty()) {
            throw new IOException("Cannot replace indexed entity UUIDs " + conflicts);
        }
        boolean[] added = {false};
        freeze.runAuthorizedEntityAddition(
                () -> added[0] = level.tryAddFreshEntityWithPassengers(replacement));
        if (!added[0]) {
            throw new IOException("Cannot add restored entity " + decoded.id());
        }
    }

    private Optional<Entity> find(UUID id) {
        return lookup.byId(Objects.requireNonNull(id, "id")).stream()
                .filter(Entity.class::isInstance)
                .map(Entity.class::cast)
                .filter(entity -> !entity.isRemoved())
                .findFirst();
    }

    private Optional<Entity> find(EntityChunkKey key, UUID id) {
        return find(id).or(() -> lookup.inChunk(key)
                .filter(Entity.class::isInstance)
                .map(Entity.class::cast)
                .filter(entity -> entity.getUUID().equals(id))
                .filter(entity -> !entity.isRemoved())
                .findFirst());
    }

    private boolean matches(
            Entity entity, EntityChunkKey key, EntityState expected) throws IOException {
        if (entity.chunkPosition().x != key.chunkX()
                || entity.chunkPosition().z != key.chunkZ()) {
            return false;
        }
        return capture.captureEntity(level, entity)
                .map(MinecraftEntityChunkCapture.CapturedEntity::state)
                .filter(expected::equals)
                .isPresent();
    }

    private void removeGraph(Entity member) throws IOException {
        Entity root = member.getRootVehicle();
        List<Entity> graph = root.getSelfAndPassengers().toList();
        if (graph.stream().anyMatch(Player.class::isInstance)) {
            throw new IOException("Cannot replace entity graph containing a player: "
                    + member.getUUID());
        }
        freeze.runAuthorized(() -> graph.forEach(entity ->
                entity.setRemoved(Entity.RemovalReason.UNLOADED_WITH_PLAYER)));
    }
}
