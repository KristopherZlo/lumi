package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.storage.TagValueOutput;

/** Copies durable non-player entities into Lumi's canonical chunk payload. */
public final class MinecraftEntityChunkCapture {
    private final MinecraftEntityNbtCanonicalizer canonicalizer;

    public MinecraftEntityChunkCapture() {
        this(new MinecraftEntityNbtCanonicalizer());
    }

    MinecraftEntityChunkCapture(MinecraftEntityNbtCanonicalizer canonicalizer) {
        this.canonicalizer = Objects.requireNonNull(canonicalizer, "canonicalizer");
    }

    public static EntityChunkKey key(ChunkPos position) {
        Objects.requireNonNull(position, "position");
        return key(position.x, position.z);
    }

    public static EntityChunkKey key(int chunkX, int chunkZ) {
        return new EntityChunkKey(chunkX, chunkZ);
    }

    public EntityChunkBlob capture(
            ServerLevel level, Stream<? extends EntityAccess> entityStream) throws IOException {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(entityStream, "entityStream");
        var captured = new ArrayList<EntityState>();
        try (entityStream) {
            for (EntityAccess access : entityStream.toList()) {
                captureEntity(level, access).ifPresent(entity -> captured.add(entity.state()));
            }
        }
        return new EntityChunkBlob(captured);
    }

    public Optional<CapturedEntity> captureEntity(
            ServerLevel level, EntityAccess access) throws IOException {
        Objects.requireNonNull(level, "level");
        if (!(Objects.requireNonNull(access, "access") instanceof Entity entity)
                || !isDurableRoot(entity)) {
            return Optional.empty();
        }
        TagValueOutput output = TagValueOutput.createWithContext(
                ProblemReporter.DISCARDING, level.registryAccess());
        if (!entity.save(output)) {
            return Optional.empty();
        }
        CompoundTag full = output.buildResult();
        EntityState state = new EntityState(
                entity.getUUID(), EntityType.getKey(entity.getType()).toString(),
                canonicalEntityNbt(full));
        return Optional.of(new CapturedEntity(
                state, new DecodedEntity(state, entity.getType(), full)));
    }

    public static boolean isDurableRoot(Entity entity) {
        return !(Objects.requireNonNull(entity, "entity") instanceof Player)
                && !entity.isPassenger()
                && entity.shouldBeSaved();
    }

    CanonicalNbt canonicalEntityNbt(CompoundTag saved) throws IOException {
        return MinecraftNbtCodec.encode(canonicalizer.normalize(saved));
    }

    EntityChunkBlob captureStored(
            EntityChunkKey key, Optional<CompoundTag> stored) throws IOException {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(stored, "stored");
        if (stored.isEmpty()) {
            return new EntityChunkBlob(List.of());
        }
        CompoundTag root = stored.orElseThrow();
        ChunkPos position = root.read("Position", ChunkPos.CODEC).orElseThrow(() ->
                new IOException("Persisted entity chunk has no Position: " + key));
        if (!position.equals(new ChunkPos(key.chunkX(), key.chunkZ()))) {
            throw new IOException("Persisted entity chunk is misplaced: " + key);
        }
        ListTag entities = root.getList("Entities").orElseThrow(() ->
                new IOException("Persisted entity chunk has no Entities list: " + key));
        var states = new ArrayList<EntityState>(entities.size());
        for (int index = 0; index < entities.size(); index++) {
            CompoundTag entity = entities.getCompound(index).orElse(null);
            if (entity == null) {
                throw new IOException("Malformed entity " + index + " in " + key);
            }
            UUID id = entity.read("UUID", UUIDUtil.CODEC).orElseThrow(() ->
                    new IOException("Entity has no valid UUID in " + key));
            String type = entity.getString("id")
                    .filter(value -> !value.isBlank()).orElseThrow(() ->
                            new IOException("Entity has no valid id in " + key));
            states.add(new EntityState(id, type, canonicalEntityNbt(entity)));
        }
        try {
            return new EntityChunkBlob(states);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed persisted entity chunk " + key, malformed);
        }
    }

    public record CapturedEntity(EntityState state, DecodedEntity decoded) {
        public CapturedEntity {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(decoded, "decoded");
        }
    }
}
