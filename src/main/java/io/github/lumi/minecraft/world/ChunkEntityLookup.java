package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.mixin.PersistentEntityManagerPersistenceAccessor;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityAccess;

/** Queries all indexed entity sections in one chunk, including inaccessible sections. */
public final class ChunkEntityLookup {
    private final Access access;

    public ChunkEntityLookup(Access access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    @SuppressWarnings("unchecked")
    public static ChunkEntityLookup forLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        var manager = ((ServerLevelEntityManagerAccessor) level).lumi$entityManager();
        var sections = ((PersistentEntityManagerPersistenceAccessor<Entity>) manager)
                .lumi$sectionStorage();
        return new ChunkEntityLookup(new Access() {
            @Override public void collect(
                    EntityChunkKey key, Consumer<EntityAccess> consumer) {
                sections.getExistingSectionsInChunk(
                                ChunkPos.asLong(key.chunkX(), key.chunkZ()))
                        .flatMap(section -> section.getEntities())
                        .forEach(consumer);
            }

            @Override public EntityAccess get(UUID id) {
                return manager.getEntityGetter().get(id);
            }

            @Override public boolean contains(UUID id) {
                return manager.isLoaded(id);
            }
        });
    }

    public Stream<EntityAccess> inChunk(EntityChunkKey key) {
        Objects.requireNonNull(key, "key");
        var matches = new ArrayList<EntityAccess>();
        access.collect(key, entity -> {
                    var position = entity.blockPosition();
                    if (position.getX() >> 4 == key.chunkX()
                            && position.getZ() >> 4 == key.chunkZ()) {
                        matches.add(entity);
                    }
                });
        return matches.stream();
    }

    public Optional<EntityAccess> byId(UUID id) {
        return Optional.ofNullable(access.get(Objects.requireNonNull(id, "id")));
    }

    public boolean isKnown(UUID id) {
        return access.contains(Objects.requireNonNull(id, "id"));
    }

    public interface Access {
        void collect(EntityChunkKey key, Consumer<EntityAccess> consumer);

        EntityAccess get(UUID id);

        boolean contains(UUID id);
    }
}
