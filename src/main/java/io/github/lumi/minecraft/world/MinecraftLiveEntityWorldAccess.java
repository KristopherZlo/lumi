package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Captures and applies durable live entities without decoding on the frozen path. */
public final class MinecraftLiveEntityWorldAccess implements LiveEntityWorldAccess {
    private final ServerLevel level;
    private final DimensionFreezeState freeze;
    private final MinecraftEntityChunkCapture capture = new MinecraftEntityChunkCapture();
    private final MinecraftEntityStateDecoder decoder = new MinecraftEntityStateDecoder(
            BuiltInRegistries.ENTITY_TYPE);
    private final ChunkEntityLookup entityLookup;
    private final MinecraftEntityRestorer entityRestorer;
    private final Map<EntityState, DecodedEntity> prepared = new HashMap<>();
    private final Map<EntityState, EntityChunkKey> chunks = new HashMap<>();

    public MinecraftLiveEntityWorldAccess(ServerLevel level, DimensionFreezeState freeze) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        entityLookup = ChunkEntityLookup.forLevel(level);
        entityRestorer = new MinecraftEntityRestorer(level, freeze, capture, entityLookup);
    }

    /** Decodes persistent Restore entities and remembers their owning chunks off-thread. */
    public Map<EntityChunkKey, EntityChunkBlob> prepareRestore(
            Map<EntityChunkKey, EntityChunkBlob> source) throws IOException {
        Map<EntityChunkKey, EntityChunkBlob> normalized = decoder.normalize(source);
        for (var entry : normalized.entrySet()) {
            var states = entry.getValue().entities();
            var decoded = decoder.decodeNormalized(entry.getValue()).entities();
            for (int index = 0; index < states.size(); index++) {
                prepared.put(states.get(index), decoded.get(index));
                chunks.put(states.get(index), entry.getKey());
            }
        }
        return normalized;
    }

    public Optional<EntityState> capture(Entity entity) throws IOException {
        if (!MinecraftEntityChunkCapture.isDurableRoot(entity) || entity.isRemoved()) {
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
    public void requirePrepared(Optional<EntityState> replacement) throws IOException {
        Optional<EntityState> missing = Objects.requireNonNull(
                replacement, "replacement").filter(state -> !prepared.containsKey(state));
        if (missing.isPresent()) {
            throw new IOException("Live entity state was not prepared before apply: "
                    + missing.orElseThrow().id());
        }
    }

    @Override
    public Optional<EntityState> read(UUID entityId) throws IOException {
        Optional<Entity> current = entityRestorer.findDurableRoot(entityId);
        return current.isEmpty() ? Optional.empty() : capture(current.orElseThrow());
    }

    @Override
    public void write(UUID entityId, Optional<EntityState> replacement) throws IOException {
        Objects.requireNonNull(entityId, "entityId");
        requirePrepared(replacement);
        if (replacement.isEmpty()) {
            entityRestorer.remove(entityId);
            return;
        }
        EntityState state = replacement.orElseThrow();
        entityRestorer.restore(chunk(state), prepared.get(state));
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
}
