package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.service.LiveEntityStateReferences;
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
    private final LiveEntityStateReferences references = new LiveEntityStateReferences(state -> {
        prepared.remove(state);
        chunks.remove(state);
    });

    public MinecraftLiveEntityWorldAccess(ServerLevel level, DimensionFreezeState freeze) {
        this.level = Objects.requireNonNull(level, "level");
        this.freeze = Objects.requireNonNull(freeze, "freeze");
        entityLookup = ChunkEntityLookup.forLevel(level);
        entityRestorer = new MinecraftEntityRestorer(level, freeze, capture, entityLookup);
    }

    public Optional<EntityState> capture(Entity entity) throws IOException {
        return capture(entity, true);
    }

    public Optional<EntityState> capture(UUID entityId) throws IOException {
        Optional<Entity> current = entityRestorer.findDurableRoot(entityId);
        return current.isEmpty() ? Optional.empty() : capture(current.orElseThrow());
    }

    public Optional<EntityChunkKey> currentChunk(UUID entityId) {
        return entityRestorer.findDurableRoot(entityId).map(entity -> new EntityChunkKey(
                entity.chunkPosition().x, entity.chunkPosition().z));
    }

    public LiveEntityStateReferences references() { return references; }

    /** Reopens a portable action endpoint off-thread, before any frozen apply begins. */
    public void prepare(EntityState state) throws IOException {
        if (prepared.containsKey(state)) return;
        var decoded = decoder.decode(new EntityChunkBlob(java.util.List.of(state), MinecraftWorldDataVersion.current()));
        if (decoded.entities().size() != 1 || !decoded.entities().getFirst().state().equals(state)) {
            throw new IOException("Action entity endpoint changed during normalization: " + state.id());
        }
        var value = decoded.entities().getFirst();
        var position = value.nbt().getListOrEmpty("Pos");
        if (position.size() != 3 || !(position.get(0) instanceof net.minecraft.nbt.DoubleTag x)
                || !(position.get(2) instanceof net.minecraft.nbt.DoubleTag z)
                || !Double.isFinite(x.value()) || !Double.isFinite(z.value())
                || Math.abs(x.value()) > 30_000_000 || Math.abs(z.value()) > 30_000_000) {
            throw new IOException("Invalid action entity position: " + state.id());
        }
        chunks.put(state, new EntityChunkKey(Math.floorDiv((int) Math.floor(x.value()), 16),
                Math.floorDiv((int) Math.floor(z.value()), 16)));
        prepared.put(state, value);
    }

    private Optional<EntityState> capture(Entity entity, boolean prepare) throws IOException {
        if (!MinecraftEntityChunkCapture.isDurableRoot(entity) || entity.isRemoved()) {
            return Optional.empty();
        }
        Optional<MinecraftEntityChunkCapture.CapturedEntity> captured =
                capture.captureEntity(level, entity);
        if (prepare) captured.ifPresent(value -> {
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
        return current.isEmpty() ? Optional.empty() : capture(current.orElseThrow(), false);
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
