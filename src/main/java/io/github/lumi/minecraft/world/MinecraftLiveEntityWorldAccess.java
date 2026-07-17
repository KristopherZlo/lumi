package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.EntityChunkKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityProcessor;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;

/** Captures and applies durable live entities without decoding on the frozen path. */
public final class MinecraftLiveEntityWorldAccess implements LiveEntityWorldAccess {
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final MinecraftEntityChunkCapture capture = new MinecraftEntityChunkCapture();
    private final ChunkEntityLookup entityLookup;
    private final Map<EntityState, DecodedEntity> prepared = new HashMap<>();
    private final Map<EntityState, EntityChunkKey> chunks = new HashMap<>();

    public MinecraftLiveEntityWorldAccess(ServerLevel level, DimensionFreezeState freeze) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        entityLookup = ChunkEntityLookup.forLevel(level);
    }

    public Optional<EntityState> capture(Entity entity) throws IOException {
        if (!isDurableRoot(entity) || entity.isRemoved()) {
            return Optional.empty();
        }
        Optional<MinecraftEntityChunkCapture.CapturedEntity> captured =
                capture.captureEntity(level, entity);
        captured.ifPresent(value -> {
            prepared.put(value.state(), value.decoded());
            chunks.put(value.state(), MinecraftEntityChunkCapture.key(
                    entity.chunkPosition().x, entity.chunkPosition().z));
        });
        return captured.map(MinecraftEntityChunkCapture.CapturedEntity::state);
    }

    @Override
    public Optional<EntityState> read(UUID entityId) throws IOException {
        Optional<Entity> current = find(entityId);
        return current.isEmpty() ? Optional.empty() : capture(current.orElseThrow());
    }

    @Override
    public void write(UUID entityId, Optional<EntityState> replacement) throws IOException {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(replacement, "replacement");
        find(entityId).ifPresent(entity -> {
            var graph = entity.getSelfAndPassengers()
                    .filter(member -> !(member instanceof Player)).toList();
            freeze.runAuthorized(() -> graph.forEach(Entity::discard));
        });
        if (replacement.isEmpty()) {
            return;
        }
        EntityState state = replacement.orElseThrow();
        DecodedEntity decoded = prepared.get(state);
        if (decoded == null) {
            throw new IOException("Live entity state was not prepared before apply: " + entityId);
        }
        Entity entity = EntityType.loadEntityRecursive(
                decoded.type(), decoded.nbt().copy(), level,
                EntitySpawnReason.LOAD, EntityProcessor.NOP);
        if (entity == null || !entity.getUUID().equals(entityId)) {
            throw new IOException("Cannot create live entity " + entityId);
        }
        boolean[] added = {false};
        freeze.runAuthorized(() -> added[0] = level.tryAddFreshEntityWithPassengers(entity));
        if (!added[0]) {
            throw new IOException("Cannot add live entity " + entityId);
        }
    }

    public void clear() {
        prepared.clear();
        chunks.clear();
    }

    public EntityChunkKey chunk(EntityState state) throws IOException {
        EntityChunkKey key = chunks.get(Objects.requireNonNull(state, "state"));
        if (key == null) {
            throw new IOException("Live entity chunk was not prepared: " + state.id());
        }
        return key;
    }

    private Optional<Entity> find(UUID entityId) {
        return entityLookup.byId(entityId).stream()
                .filter(Entity.class::isInstance)
                .map(Entity.class::cast)
                .filter(MinecraftLiveEntityWorldAccess::isDurableRoot)
                .filter(entity -> !entity.isRemoved())
                .findFirst();
    }

    private static boolean isDurableRoot(Entity entity) {
        return !(entity instanceof Player) && !entity.isPassenger() && entity.shouldBeSaved();
    }
}
