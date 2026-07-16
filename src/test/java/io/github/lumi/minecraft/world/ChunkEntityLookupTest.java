package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.EntityChunkKey;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class ChunkEntityLookupTest {
    @Test
    void queriesOnlyTheChunkBoundsAndFiltersNeighboringChonkyEntities() {
        FakeEntity inside = new FakeEntity(new UUID(0, 1), new BlockPos(35, 70, -29));
        FakeEntity neighbor = new FakeEntity(new UUID(0, 2), new BlockPos(48, 70, -29));
        RecordingAccess access = new RecordingAccess(List.of(inside, neighbor));
        ChunkEntityLookup lookup = new ChunkEntityLookup(-64, 320, access);

        List<UUID> result = lookup.inChunk(new EntityChunkKey(2, -2))
                .map(EntityAccess::getUUID).toList();

        assertEquals(List.of(inside.getUUID()), result);
        assertEquals(new AABB(32, -64, -32, 48, 320, -16), access.bounds);
        assertEquals(1, access.collectCalls);
        assertEquals(inside, lookup.byId(inside.getUUID()).orElseThrow());
    }

    private static final class RecordingAccess implements ChunkEntityLookup.Access {
        private final List<EntityAccess> candidates;
        private int collectCalls;
        private AABB bounds;

        private RecordingAccess(List<EntityAccess> candidates) {
            this.candidates = new ArrayList<>(candidates);
        }

        @Override public void collect(AABB bounds, Consumer<EntityAccess> consumer) {
            collectCalls++;
            this.bounds = bounds;
            candidates.forEach(consumer);
        }

        @Override public EntityAccess get(UUID id) {
            return candidates.stream().filter(entity -> entity.getUUID().equals(id))
                    .findFirst().orElse(null);
        }
    }

    private static final class FakeEntity implements EntityAccess {
        private final UUID id;
        private final BlockPos position;

        private FakeEntity(UUID id, BlockPos position) {
            this.id = id;
            this.position = position;
        }

        @Override public int getId() { return id.hashCode(); }
        @Override public UUID getUUID() { return id; }
        @Override public BlockPos blockPosition() { return position; }
        @Override public AABB getBoundingBox() { return new AABB(position); }
        @Override public void setLevelCallback(EntityInLevelCallback callback) { }
        @Override public Stream<? extends EntityAccess> getSelfAndPassengers() {
            return Stream.of(this);
        }
        @Override public Stream<? extends EntityAccess> getPassengersAndSelf() {
            return Stream.of(this);
        }
        @Override public void setRemoved(Entity.RemovalReason reason) { }
        @Override public boolean shouldBeSaved() { return true; }
        @Override public boolean isAlwaysTicking() { return false; }
        @Override public boolean isRemoved() { return false; }
    }
}
