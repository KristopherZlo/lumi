package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.mixin.ServerLevelEntityManagerAccessor;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.phys.AABB;

/** Uses Minecraft's entity-section index instead of scanning every entity in a level. */
public final class ChunkEntityLookup {
    private final int minY;
    private final int maxY;
    private final Access access;

    public ChunkEntityLookup(int minY, int maxY, Access access) {
        this.minY = minY;
        this.maxY = maxY;
        this.access = Objects.requireNonNull(access, "access");
    }

    public static ChunkEntityLookup forLevel(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        var getter = ((ServerLevelEntityManagerAccessor) level)
                .lumi$entityManager().getEntityGetter();
        return new ChunkEntityLookup(level.getMinY(), level.getMaxY(), new Access() {
            @Override public void collect(AABB bounds, Consumer<EntityAccess> consumer) {
                getter.get(bounds, entity -> consumer.accept(entity));
            }

            @Override public EntityAccess get(UUID id) {
                return getter.get(id);
            }
        });
    }

    public Stream<EntityAccess> inChunk(EntityChunkKey key) {
        Objects.requireNonNull(key, "key");
        int minX = key.chunkX() << 4;
        int minZ = key.chunkZ() << 4;
        var matches = new ArrayList<EntityAccess>();
        access.collect(new AABB(
                minX, minY, minZ, minX + 16, maxY, minZ + 16), entity -> {
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

    public interface Access {
        void collect(AABB bounds, Consumer<EntityAccess> consumer);

        EntityAccess get(UUID id);
    }
}
