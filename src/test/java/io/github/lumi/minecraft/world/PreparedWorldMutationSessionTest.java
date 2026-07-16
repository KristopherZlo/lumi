package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.PlayerSpawn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PreparedWorldMutationSessionTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void appliesBlocksIncrementallyThenVerifiesExactSection() throws Exception {
        SectionKey key = new SectionKey(1, 2, 3);
        SectionBlob source = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);
        PreparedMinecraftState target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(key, source), Map.of()),
                Map.of(key, decoded), Map.of());
        AtomicLong clock = new AtomicLong();
        FakeWorld world = new FakeWorld(clock, source);
        PreparedWorldMutationSession session =
                new PreparedWorldMutationSession(target, world, clock::get);

        assertFalse(session.applyUntil(3));
        assertEquals(3, world.blockWrites);
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(SectionBlob.BLOCK_COUNT, world.blockWrites);
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
    }

    @Test
    void atomicallyReplacesAndVerifiesDurableEntityChunk() throws Exception {
        EntityChunkKey key = new EntityChunkKey(4, -2);
        UUID oldId = new UUID(0, 1);
        UUID targetId = new UUID(0, 2);
        var nbt = new net.minecraft.nbt.CompoundTag();
        var source = new EntityChunkBlob(List.of(new EntityState(
                targetId, "minecraft:armor_stand", MinecraftNbtCodec.encode(nbt))));
        DecodedEntityChunk decoded = new MinecraftEntityStateDecoder(
                BuiltInRegistries.ENTITY_TYPE).decode(source);
        PreparedMinecraftState target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), Map.of(key, source)),
                Map.of(), Map.of(key, decoded));
        AtomicLong clock = new AtomicLong();
        FakeWorld world = new FakeWorld(clock, null);
        world.entityIds = List.of(oldId);
        world.capturedEntities = source;
        PreparedWorldMutationSession session =
                new PreparedWorldMutationSession(target, world, clock::get);

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(oldId), world.removedEntities);
        assertEquals(List.of(targetId), world.addedEntities);
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
    }

    @Test
    void appliesAndVerifiesSavedPlayerSpawns() throws Exception {
        UUID player = UUID.fromString("10000000-0000-0000-0000-000000000001");
        PlayerSpawn spawn = new PlayerSpawn(8, 72, -3, 45.0F, 5.0F, true);
        PreparedMinecraftState target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), Map.of(), Map.of(player, spawn)),
                Map.of(), Map.of());
        FakeWorld world = new FakeWorld(new AtomicLong(), null);
        PreparedWorldMutationSession session =
                new PreparedWorldMutationSession(target, world, () -> 0L);

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(Map.of(player, spawn), world.playerSpawns);
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
    }

    private static final class FakeWorld implements PreparedWorldAccess {
        private final AtomicLong clock;
        private final SectionBlob captured;
        private int blockWrites;
        private List<UUID> entityIds = List.of();
        private EntityChunkBlob capturedEntities = new EntityChunkBlob(List.of());
        private final List<UUID> removedEntities = new ArrayList<>();
        private final List<UUID> addedEntities = new ArrayList<>();
        private Map<UUID, PlayerSpawn> playerSpawns = Map.of();

        private FakeWorld(AtomicLong clock, SectionBlob captured) {
            this.clock = clock;
            this.captured = captured;
        }

        @Override public void setBlock(
                SectionKey key, int localIndex,
                net.minecraft.world.level.block.state.BlockState state) {
            blockWrites++;
            clock.incrementAndGet();
        }
        @Override public List<Integer> blockEntityIndexes(SectionKey key) { return List.of(); }
        @Override public void removeBlockEntity(SectionKey key, int localIndex) { }
        @Override public void loadBlockEntity(
                SectionKey key, int localIndex, net.minecraft.nbt.CompoundTag nbt) { }
        @Override public SectionBlob captureSection(SectionKey key) { return captured; }
        @Override public List<UUID> durableEntityIds(EntityChunkKey key) { return entityIds; }
        @Override public void removeEntity(EntityChunkKey key, UUID id) {
            removedEntities.add(id);
            clock.incrementAndGet();
        }
        @Override public void addEntity(EntityChunkKey key, DecodedEntity entity) {
            addedEntities.add(entity.id());
            clock.incrementAndGet();
        }
        @Override public EntityChunkBlob captureEntities(EntityChunkKey key) {
            return capturedEntities;
        }
        @Override public void applyPlayerSpawns(Map<UUID, PlayerSpawn> spawns) {
            playerSpawns = Map.copyOf(spawns);
        }
        @Override public boolean matchesPlayerSpawns(Map<UUID, PlayerSpawn> spawns) {
            return playerSpawns.equals(spawns);
        }
    }
}
