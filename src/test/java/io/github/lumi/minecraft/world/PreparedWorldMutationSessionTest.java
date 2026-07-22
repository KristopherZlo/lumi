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
import java.util.concurrent.CompletableFuture;
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
    void appliesOnePreparedSectionMutationThenVerifiesExactSection() throws Exception {
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

        assertFalse(session.applyUntil(1));
        assertEquals(1, world.sectionWrites);
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(1, world.sectionWrites);
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
    void removesEveryAffectedEntityBeforeAddingMovedEntities() throws Exception {
        EntityChunkKey first = new EntityChunkKey(4, -2);
        EntityChunkKey second = new EntityChunkKey(5, -2);
        UUID firstOld = new UUID(0, 1);
        UUID secondOld = new UUID(0, 2);
        UUID firstTarget = new UUID(0, 3);
        UUID secondTarget = new UUID(0, 4);
        var emptyNbt = MinecraftNbtCodec.encode(new net.minecraft.nbt.CompoundTag());
        var firstSource = new EntityChunkBlob(List.of(
                new EntityState(firstTarget, "minecraft:armor_stand", emptyNbt)));
        var secondSource = new EntityChunkBlob(List.of(
                new EntityState(secondTarget, "minecraft:armor_stand", emptyNbt)));
        var decoder = new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE);
        PreparedMinecraftState target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), Map.of(
                        first, firstSource, second, secondSource)),
                Map.of(), Map.of(
                        first, decoder.decode(firstSource),
                        second, decoder.decode(secondSource)));
        FakeWorld world = new FakeWorld(new AtomicLong(), null);
        world.entityIdsByChunk = Map.of(
                first, List.of(firstOld),
                second, List.of(secondOld));
        PreparedWorldMutationSession session =
                new PreparedWorldMutationSession(target, world, () -> 0L);

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertTrue(world.entityMutations.subList(0, 2).stream()
                .allMatch(mutation -> mutation.startsWith("remove:")));
        assertTrue(world.entityMutations.subList(2, 4).stream()
                .allMatch(mutation -> mutation.startsWith("add:")));
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

    @Test
    void loadsRequiredChunkBeforeTheFirstWorldMutation() throws Exception {
        SectionKey key = new SectionKey(7, 0, -3);
        SectionBlob source = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);
        PreparedMinecraftState target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(key, source), Map.of()),
                Map.of(key, decoded), Map.of());
        AtomicLong clock = new AtomicLong();
        FakeWorld world = new FakeWorld(clock, source);
        RecordingChunkAccess access = new RecordingChunkAccess();
        ChunkLoadSession chunks = new ChunkLoadSession(access, clock::get);
        PreparedWorldMutationSession session =
                new PreparedWorldMutationSession(target, world, clock::get, chunks);

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(0, world.sectionWrites);
        assertEquals(List.of(new ChunkCoordinate(7, -3)), access.retained);

        access.loaded.complete(null);
        access.ready = true;
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        session.close();
        assertEquals(List.of(new ChunkCoordinate(7, -3)), access.released);
    }

    @Test
    void synchronizesAllSectionsInOneChunkOnce() throws Exception {
        SectionKey low = new SectionKey(1, 0, 2);
        SectionKey high = new SectionKey(1, 1, 2);
        SectionKey other = new SectionKey(3, 0, 4);
        SectionBlob source = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);
        var persistent = new WorldStateApply.State(
                Map.of(low, source, high, source, other, source), Map.of());
        var target = new PreparedMinecraftState(
                persistent,
                Map.of(low, decoded, high, decoded, other, decoded),
                Map.of(), List.of(low, high, other), List.of());
        FakeWorld world = new FakeWorld(new AtomicLong(), source);

        assertTrue(new PreparedWorldMutationSession(target, world, () -> 0L)
                .applyUntil(Long.MAX_VALUE));

        assertEquals(3, world.sectionWrites);
        assertEquals(2, world.synchronizedChunks);
    }

    private static final class FakeWorld implements PreparedWorldAccess {
        private final AtomicLong clock;
        private final SectionBlob captured;
        private int sectionWrites;
        private int synchronizedChunks;
        private List<UUID> entityIds = List.of();
        private Map<EntityChunkKey, List<UUID>> entityIdsByChunk = Map.of();
        private EntityChunkBlob capturedEntities = new EntityChunkBlob(List.of());
        private final List<UUID> removedEntities = new ArrayList<>();
        private final List<UUID> addedEntities = new ArrayList<>();
        private final List<String> entityMutations = new ArrayList<>();
        private Map<UUID, PlayerSpawn> playerSpawns = Map.of();

        private FakeWorld(AtomicLong clock, SectionBlob captured) {
            this.clock = clock;
            this.captured = captured;
        }

        @Override public SectionApplyResult applySection(
                SectionKey key, DecodedSection section) {
            sectionWrites++;
            clock.incrementAndGet();
            return new SectionApplyResult(key, new short[] {0}, 1);
        }
        @Override public void finishChunk(
                ChunkCoordinate chunk,
                List<SectionApplyResult> sections,
                boolean blockEntitiesChanged) {
            synchronizedChunks++;
        }
        @Override public List<Integer> blockEntityIndexes(SectionKey key) { return List.of(); }
        @Override public void removeBlockEntity(SectionKey key, int localIndex) { }
        @Override public void loadBlockEntity(
                SectionKey key, int localIndex, net.minecraft.nbt.CompoundTag nbt) { }
        @Override public SectionBlob captureSection(SectionKey key) { return captured; }
        @Override public List<UUID> durableEntityIds(EntityChunkKey key) {
            return entityIdsByChunk.getOrDefault(key, entityIds);
        }
        @Override public void removeEntity(EntityChunkKey key, UUID id) {
            removedEntities.add(id);
            entityMutations.add("remove:" + id);
            clock.incrementAndGet();
        }
        @Override public void addEntity(EntityChunkKey key, DecodedEntity entity) {
            addedEntities.add(entity.id());
            entityMutations.add("add:" + entity.id());
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

    private static final class RecordingChunkAccess implements ChunkLoadAccess {
        private final CompletableFuture<Void> loaded = new CompletableFuture<>();
        private final List<ChunkCoordinate> retained = new ArrayList<>();
        private final List<ChunkCoordinate> released = new ArrayList<>();
        private boolean ready;

        @Override public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
            retained.add(chunk);
            return loaded;
        }
        @Override public boolean isReady(ChunkCoordinate chunk) { return ready; }
        @Override public void release(ChunkCoordinate chunk) { released.add(chunk); }
    }
}
