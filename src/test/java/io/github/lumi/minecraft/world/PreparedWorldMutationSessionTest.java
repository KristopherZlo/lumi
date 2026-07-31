package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.service.RestorePlanMap;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
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
        world.storedResult = StoredChunkApplyResult.fallback(
                StoredChunkApplyResult.Outcome.RESIDENT);
        PreparedWorldMutationSession session =
                new PreparedWorldMutationSession(target, world, clock::get);

        assertFalse(session.applyUntil(1));
        assertEquals(1, world.sectionWrites);
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(1, world.sectionWrites);
        assertEquals(Map.of(StoredChunkApplyResult.Outcome.RESIDENT, 1L),
                session.statistics().storedFallbacks());
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
    void retainsEntityChunksAcrossTheGlobalRemoveAndAddPasses() throws Exception {
        EntityChunkKey key = new EntityChunkKey(4, -2);
        UUID oldId = new UUID(0, 1);
        UUID targetId = new UUID(0, 2);
        var source = new EntityChunkBlob(List.of(new EntityState(
                targetId, "minecraft:armor_stand",
                MinecraftNbtCodec.encode(new net.minecraft.nbt.CompoundTag()))));
        DecodedEntityChunk decoded = new MinecraftEntityStateDecoder(
                BuiltInRegistries.ENTITY_TYPE).decode(source);
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), Map.of(key, source)),
                Map.of(), Map.of(key, decoded));
        FakeWorld world = new FakeWorld(new AtomicLong(), null);
        world.entityIds = List.of(oldId);
        world.capturedEntities = source;
        ImmediateChunkAccess access = new ImmediateChunkAccess();
        var session = new PreparedWorldMutationSession(
                target, world, () -> 0L,
                new ChunkLoadSession(access, () -> 0L));

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(1, access.active);
        assertEquals(List.of(), access.released);
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
        assertEquals(1, access.active);
        assertEquals(List.of(), access.released);
        ManualPersistence persistence = new ManualPersistence();
        persistence.timings = new WorldPersistenceSession.Timings(5, 7, 11);
        world.persistence = persistence;
        assertFalse(session.persistUntil(Long.MAX_VALUE));
        assertEquals(1, access.active);
        persistence.accepted = List.of(new ChunkCoordinate(4, -2));
        assertFalse(session.persistUntil(Long.MAX_VALUE));
        assertEquals(0, access.active);
        persistence.complete = true;
        assertTrue(session.persistUntil(Long.MAX_VALUE));
        assertEquals(5, session.statistics().storageWriteNanos());
        assertEquals(7, session.statistics().storageSyncNanos());
        assertEquals(11, session.statistics().verificationNanos());
        assertTrue(session.persistUntil(Long.MAX_VALUE));
        assertEquals(5, session.statistics().storageWriteNanos());
        assertEquals(0, access.active);
        session.close();
        assertEquals(0, access.active);
        assertEquals(List.of(new ChunkCoordinate(4, -2)), access.released);
    }

    @Test
    void releasesAcceptedSnapshotsWhenPersistenceFails() throws Exception {
        EntityChunkKey key = new EntityChunkKey(4, -2);
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), Map.of(key, empty)),
                Map.of(), Map.of(key, new DecodedEntityChunk(List.of())));
        var world = new FakeWorld(new AtomicLong(), null);
        var access = new ImmediateChunkAccess();
        var session = new PreparedWorldMutationSession(
                target, world, () -> 0L,
                new ChunkLoadSession(access, () -> 0L));
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
        var persistence = new ManualPersistence();
        persistence.accepted = List.of(new ChunkCoordinate(4, -2));
        persistence.failure = new IOException("expected");
        world.persistence = persistence;

        IOException failed = assertThrows(
                IOException.class, () -> session.persistUntil(Long.MAX_VALUE));

        assertEquals("expected", failed.getMessage());
        assertEquals(0, access.active);
        session.close();
        assertEquals(List.of(new ChunkCoordinate(4, -2)), access.released);
    }

    @Test
    void rejectsPersistenceAfterAVerificationMismatch() throws Exception {
        EntityChunkKey key = new EntityChunkKey(4, -2);
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), Map.of(key, empty)),
                Map.of(), Map.of(key, new DecodedEntityChunk(List.of())));
        FakeWorld world = new FakeWorld(new AtomicLong(), null);
        world.capturedEntities = new EntityChunkBlob(List.of(new EntityState(
                UUID.randomUUID(), "minecraft:armor_stand",
                MinecraftNbtCodec.encode(new net.minecraft.nbt.CompoundTag()))));
        var session = new PreparedWorldMutationSession(
                target, world, () -> 0L, null, new RestoreApplyMetrics());

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(WorldStateApply.Verification.MISMATCH,
                session.verifyUntil(Long.MAX_VALUE));
        assertTrue(session.mismatch().startsWith("entity chunk " + key));
        assertThrows(IllegalStateException.class,
                () -> session.persistUntil(Long.MAX_VALUE));
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
    void bulkReadinessWaitsForStoredClassificationAndEveryLiveChunk() throws Exception {
        SectionKey storedKey = new SectionKey(6, 0, -3);
        SectionKey sectionKey = new SectionKey(7, 0, -3);
        EntityChunkKey entityKey = new EntityChunkKey(8, -3);
        SectionBlob section = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(section);
        EntityChunkBlob entities = new EntityChunkBlob(List.of());
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(
                        Map.of(storedKey, section, sectionKey, section),
                        Map.of(entityKey, entities)),
                Map.of(storedKey, decoded, sectionKey, decoded),
                Map.of(entityKey, new DecodedEntityChunk(List.of())),
                List.of(storedKey, sectionKey), List.of(entityKey));
        AtomicLong clock = new AtomicLong();
        FakeWorld world = new FakeWorld(clock, section);
        world.storedBatchResult = new CompletableFuture<>();
        ManualPersistence persistence = new ManualPersistence();
        world.persistence = persistence;
        ControlledChunkAccess access = new ControlledChunkAccess();
        var session = new PreparedWorldMutationSession(
                target, world, clock::get, new ChunkLoadSession(access, clock::get));
        ChunkCoordinate storedChunk = ChunkCoordinate.from(storedKey);
        ChunkCoordinate sectionChunk = ChunkCoordinate.from(sectionKey);
        ChunkCoordinate entityChunk = ChunkCoordinate.from(entityKey);

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(), access.retained);
        assertEquals(0, world.sectionWrites);

        world.storedBatchResult.complete(Map.of(
                storedChunk, StoredChunkApplyResult.APPLIED,
                sectionChunk, StoredChunkApplyResult.fallback(
                        StoredChunkApplyResult.Outcome.RESIDENT)));
        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(sectionChunk, entityChunk), access.retained);
        assertEquals(0, world.sectionWrites);
        assertEquals(List.of(), world.startedEntityChunks);

        access.complete(sectionChunk);
        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(0, world.sectionWrites);
        clock.set(25);
        access.complete(entityChunk);
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(1, world.sectionWrites);
        assertEquals(25, session.statistics().chunkLoadNanos());
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
        assertFalse(session.persistUntil(Long.MAX_VALUE));
        assertEquals(2, access.active());
        persistence.complete = true;
        assertTrue(session.persistUntil(Long.MAX_VALUE));
        assertEquals(2, access.active());

        session.close();
        session.close();
        assertEquals(List.of(sectionChunk, entityChunk), access.released);
        assertEquals(1, persistence.closeCalls);
    }

    @Test
    void bulkLoadsAnEntityOnlyWindowBeforeItsFirstMutation() throws Exception {
        EntityChunkKey first = new EntityChunkKey(1, 1);
        EntityChunkKey second = new EntityChunkKey(2, 2);
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), Map.of(first, empty, second, empty)),
                Map.of(), Map.of(
                        first, new DecodedEntityChunk(List.of()),
                        second, new DecodedEntityChunk(List.of())),
                List.of(), List.of(first, second));
        FakeWorld world = new FakeWorld(new AtomicLong(), null);
        ControlledChunkAccess access = new ControlledChunkAccess();
        var session = new PreparedWorldMutationSession(
                target, world, () -> 0L,
                new ChunkLoadSession(access, () -> 0L));

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(
                ChunkCoordinate.from(first), ChunkCoordinate.from(second)),
                access.retained);
        assertEquals(List.of(), world.startedEntityChunks);

        access.complete(ChunkCoordinate.from(first));
        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(), world.startedEntityChunks);
        access.complete(ChunkCoordinate.from(second));
        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(first, second), world.startedEntityChunks);
        session.close();
    }

    @Test
    void keepsTheSequentialLoaderForStatesLargerThanOneWindow() throws Exception {
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        Map<EntityChunkKey, EntityChunkBlob> persistent = new LinkedHashMap<>();
        Map<EntityChunkKey, DecodedEntityChunk> decoded = new LinkedHashMap<>();
        List<EntityChunkKey> keys = new ArrayList<>();
        for (int chunkX = 0; chunkX < 33; chunkX++) {
            EntityChunkKey key = new EntityChunkKey(chunkX, 0);
            keys.add(key);
            persistent.put(key, empty);
            decoded.put(key, new DecodedEntityChunk(List.of()));
        }
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(), persistent),
                Map.of(), decoded, List.of(), keys);
        RecordingChunkAccess access = new RecordingChunkAccess();
        var session = new PreparedWorldMutationSession(
                target, new FakeWorld(new AtomicLong(), null), () -> 0L,
                new ChunkLoadSession(access, () -> 0L));

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(ChunkCoordinate.from(keys.getFirst())), access.retained);
        session.close();
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

    @Test
    void retainsLoadedSectionChunksUntilPersistenceCompletes() throws Exception {
        SectionBlob source = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);
        Map<SectionKey, SectionBlob> persistentSections = new HashMap<>();
        Map<SectionKey, DecodedSection> decodedSections = new HashMap<>();
        List<SectionKey> order = new ArrayList<>();
        for (int chunkX = 0; chunkX < 2; chunkX++) {
            SectionKey key = new SectionKey(chunkX, 0, 0);
            persistentSections.put(key, source);
            decodedSections.put(key, decoded);
            order.add(key);
        }
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(persistentSections, Map.of()),
                decodedSections, Map.of(), order, List.of());
        var access = new ImmediateChunkAccess();
        var session = new PreparedWorldMutationSession(
                target, new FakeWorld(new AtomicLong(), source), () -> 0L,
                new ChunkLoadSession(access, () -> 0L));

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(2, access.peakRetained);
        assertEquals(2, access.active);
        assertEquals(List.of(), access.released);
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
        assertTrue(session.persistUntil(Long.MAX_VALUE));
        assertEquals(2, access.active);
        session.close();
        assertEquals(0, access.active);
        assertEquals(2, access.released.size());
        assertEquals(2, session.statistics().loadedChunks());
        assertEquals(2, session.statistics().sectionSwaps());
    }

    @Test
    void skipsFullLoadingAndReadbackAfterVerifiedStoredApply() throws Exception {
        SectionKey low = new SectionKey(12, 0, -8);
        SectionKey high = new SectionKey(12, 1, -8);
        SectionBlob source = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(Map.of(low, source, high, source), Map.of()),
                Map.of(low, decoded, high, decoded), Map.of(),
                List.of(low, high), List.of());
        FakeWorld world = new FakeWorld(new AtomicLong(), source);
        world.storedResult = StoredChunkApplyResult.applied(
                1, 2, 3, 4, 2, 8_192, 1);
        ImmediateChunkAccess chunks = new ImmediateChunkAccess();
        var session = new PreparedWorldMutationSession(
                target, world, () -> 0L, new ChunkLoadSession(chunks, () -> 0L));

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(1, world.storedWrites);
        assertEquals(0, world.sectionWrites);
        assertEquals(0, chunks.retained.size());
        assertEquals(1, session.statistics().storedChunks());
        assertEquals(2, session.statistics().sectionSwaps());
        assertEquals(8_192, session.statistics().changedBlocks());
        assertEquals(1, session.statistics().lightSections());
        assertEquals(1, session.statistics().storageReadNanos());
        assertEquals(2, session.statistics().storageWriteNanos());
        assertEquals(3, session.statistics().storageSyncNanos());
        assertEquals(4, session.statistics().verificationNanos());
        assertEquals(WorldStateApply.Verification.VERIFIED,
                session.verifyUntil(Long.MAX_VALUE));
    }

    @Test
    void groupsStoredWritesIntoWindowsOfThirtyTwoChunks() throws Exception {
        SectionBlob source = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        DecodedSection decoded = new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK)
                .decode(source);
        Map<SectionKey, SectionBlob> persistent = new LinkedHashMap<>();
        Map<SectionKey, DecodedSection> prepared = new LinkedHashMap<>();
        List<SectionKey> order = new ArrayList<>();
        for (int chunkX = 0; chunkX < 40; chunkX++) {
            SectionKey key = new SectionKey(chunkX, 0, 0);
            persistent.put(key, source);
            prepared.put(key, decoded);
            order.add(key);
        }
        var target = new PreparedMinecraftState(
                new WorldStateApply.State(persistent, Map.of()), prepared,
                Map.of(), order, List.of());
        FakeWorld world = new FakeWorld(new AtomicLong(), source);
        world.storedResult = StoredChunkApplyResult.APPLIED;
        var session = new PreparedWorldMutationSession(
                target, world, () -> 0L,
                new ChunkLoadSession(new ImmediateChunkAccess(), () -> 0L));

        assertTrue(session.applyUntil(Long.MAX_VALUE));
        assertEquals(2, world.storedBatches);
        assertEquals(32, world.maxStoredBatch);
        assertEquals(40, session.statistics().storedChunks());
    }

    @Test
    void streamingDoesNotStartTheNextBatchBeforePersistence() throws Exception {
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        Map<EntityChunkKey, EntityChunkBlob> persistent = new LinkedHashMap<>();
        Map<EntityChunkKey, DecodedEntityChunk> decoded = new LinkedHashMap<>();
        List<EntityChunkKey> keys = new ArrayList<>();
        for (int chunkX = 0; chunkX < 33; chunkX++) {
            EntityChunkKey key = new EntityChunkKey(chunkX, 0);
            keys.add(key);
            persistent.put(key, empty);
            decoded.put(key, new DecodedEntityChunk(List.of()));
        }
        var state = new WorldStateApply.State(Map.of(), persistent);
        var plan = new PreparedMinecraftPlanState(
                state, state, decoded, decoded, List.of(), keys, Set.of());
        var world = new FakeWorld(new AtomicLong(), null);
        var persistence = new ManualPersistence();
        world.persistence = persistence;
        world.lightingComplete = false;
        List<ChunkLoadAccess.Readiness> requested = new ArrayList<>();

        try (var session = new StreamingPreparedWorldMutationSession(
                plan,
                new MinecraftRestorePreparation(
                        new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                        new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE)),
                world, Runnable::run, readiness -> {
                    requested.add(readiness);
                    return null;
                })) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertTrue(world.startedEntityChunks.isEmpty());
            assertEquals(0, world.persistenceStarts);

            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(keys.subList(0, 32), world.startedEntityChunks);
            assertEquals(1, world.persistenceStarts);
            assertEquals(List.of(
                    ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES), requested);

            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(keys.subList(0, 32), world.startedEntityChunks);
            assertEquals(1, world.persistenceStarts);

            persistence.complete = true;
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(keys, world.startedEntityChunks);
            assertEquals(1, world.lightingChecks);
            assertEquals(List.of(
                    ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES,
                    ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES), requested);
            assertEquals(List.of(
                    PreparedWorldMutationSession.PersistenceMode.STAGE,
                    PreparedWorldMutationSession.PersistenceMode.STAGE,
                    PreparedWorldMutationSession.PersistenceMode.FINAL),
                    world.persistenceModes);

            world.lightingComplete = true;
            assertTrue(session.applyUntil(Long.MAX_VALUE));
            assertEquals(2, world.lightingChecks);
        }
    }

    @Test
    void streamingDefersLazySectionReadsToTheBackgroundExecutor() throws Exception {
        SectionKey key = new SectionKey(2, 0, 3);
        SectionBlob section = new SectionBlob(new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:stone")), Map.of());
        AtomicInteger targetReads = new AtomicInteger();
        AtomicInteger baseReads = new AtomicInteger();
        Map<SectionKey, SectionBlob> target = new RestorePlanMap<>(
                Set.of(key), ignored -> {
                    targetReads.incrementAndGet();
                    return section;
                });
        Map<SectionKey, SectionBlob> base = new RestorePlanMap<>(
                Set.of(key), ignored -> {
                    baseReads.incrementAndGet();
                    return section;
                });
        var plan = new PreparedMinecraftPlanState(
                new WorldStateApply.State(target, Map.of()),
                new WorldStateApply.State(base, Map.of()),
                Map.of(), Map.of(), List.of(key), List.of(), Set.of());
        ArrayDeque<Runnable> background = new ArrayDeque<>();

        try (var session = new StreamingPreparedWorldMutationSession(
                plan,
                new MinecraftRestorePreparation(
                        new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                        new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE)),
                new FakeWorld(new AtomicLong(), section), background::add,
                ignored -> new ChunkLoadSession(
                        new ImmediateChunkAccess(), () -> 0L))) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(0, targetReads.get());
            assertEquals(0, baseReads.get());
            assertEquals(1, background.size());

            background.remove().run();

            assertEquals(1, targetReads.get());
            assertEquals(1, baseReads.get());
            assertTrue(session.applyUntil(Long.MAX_VALUE));
        }
    }

    @Test
    void streamingOmitsPlayerPathWhenSpawnsAreNotPartOfRestore() throws Exception {
        EntityChunkKey key = new EntityChunkKey(1, 2);
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        var state = new WorldStateApply.State(Map.of(), Map.of(key, empty));
        var plan = new PreparedMinecraftPlanState(
                state, state,
                Map.of(key, new DecodedEntityChunk(List.of())),
                Map.of(key, new DecodedEntityChunk(List.of())),
                List.of(), List.of(key), Set.of());
        var world = new FakeWorld(new AtomicLong(), null);

        try (var session = new StreamingPreparedWorldMutationSession(
                plan,
                new MinecraftRestorePreparation(
                        new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                        new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE)),
                world, Runnable::run, ignored -> null)) {
            assertTrue(session.applyUntil(Long.MAX_VALUE));
        }

        assertEquals(0, world.playerSpawnWrites);
        assertEquals(0, world.playerSpawnMatches);
        assertEquals(List.of(false, false), world.persistencePlayerSpawnFlags);
    }

    private static final class FakeWorld implements PreparedWorldAccess {
        private final AtomicLong clock;
        private final SectionBlob captured;
        private int sectionWrites;
        private int synchronizedChunks;
        private int storedWrites;
        private int storedBatches;
        private int maxStoredBatch;
        private StoredChunkApplyResult storedResult = StoredChunkApplyResult.FALLBACK;
        private CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>>
                storedBatchResult;
        private List<UUID> entityIds = List.of();
        private Map<EntityChunkKey, List<UUID>> entityIdsByChunk = Map.of();
        private EntityChunkBlob capturedEntities = new EntityChunkBlob(List.of());
        private final List<UUID> removedEntities = new ArrayList<>();
        private final List<UUID> addedEntities = new ArrayList<>();
        private final List<String> entityMutations = new ArrayList<>();
        private final List<EntityChunkKey> startedEntityChunks = new ArrayList<>();
        private Map<UUID, PlayerSpawn> playerSpawns = Map.of();
        private WorldPersistenceSession persistence = WorldPersistenceSession.COMPLETE;
        private int persistenceStarts;
        private int playerSpawnWrites;
        private int playerSpawnMatches;
        private boolean lightingComplete = true;
        private int lightingChecks;
        private final List<Boolean> persistencePlayerSpawnFlags = new ArrayList<>();
        private final List<PreparedWorldMutationSession.PersistenceMode> persistenceModes =
                new ArrayList<>();

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

        @Override public WorldPersistenceSession beginPersistence(
                PreparedMinecraftState target,
                Set<ChunkCoordinate> alreadyDurable,
                boolean playerSpawnsIncluded) {
            return persistence(
                    PreparedWorldMutationSession.PersistenceMode.COMPLETE,
                    playerSpawnsIncluded);
        }
        @Override public WorldPersistenceSession beginPersistenceStage(
                PreparedMinecraftState target,
                Set<ChunkCoordinate> alreadyDurable) {
            return persistence(
                    PreparedWorldMutationSession.PersistenceMode.STAGE, false);
        }
        @Override public WorldPersistenceSession beginPersistenceCommit(
                PreparedMinecraftState target,
                WorldStateApply.State verificationTarget,
                List<SectionKey> verificationSections,
                List<EntityChunkKey> verificationEntities,
                Set<ChunkCoordinate> alreadyDurable) {
            return persistence(
                    PreparedWorldMutationSession.PersistenceMode.FINAL, false);
        }
        private WorldPersistenceSession persistence(
                PreparedWorldMutationSession.PersistenceMode mode,
                boolean playerSpawnsIncluded) {
            persistenceStarts++;
            persistenceModes.add(mode);
            persistencePlayerSpawnFlags.add(playerSpawnsIncluded);
            return persistence;
        }
        @Override public boolean finishLighting() {
            lightingChecks++;
            return lightingComplete;
        }
        @Override public ChunkSyncResult finishChunk(
                ChunkCoordinate chunk,
                List<SectionApplyResult> sections,
                boolean blockEntitiesChanged) {
            synchronizedChunks++;
            return ChunkSyncResult.NONE;
        }
        @Override public CompletableFuture<StoredChunkApplyResult> applyStoredChunk(
                ChunkCoordinate chunk,
                Map<SectionKey, DecodedSection> sections,
                boolean entitiesChanged) {
            storedWrites++;
            return CompletableFuture.completedFuture(storedResult);
        }
        @Override public CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>>
                applyStoredChunks(
                        Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> chunks,
                        Set<ChunkCoordinate> entityChunks) {
            storedBatches++;
            maxStoredBatch = Math.max(maxStoredBatch, chunks.size());
            storedWrites += chunks.size();
            if (storedBatchResult != null) {
                return storedBatchResult;
            }
            Map<ChunkCoordinate, StoredChunkApplyResult> results = new LinkedHashMap<>();
            chunks.keySet().forEach(chunk -> results.put(chunk, storedResult));
            return CompletableFuture.completedFuture(Map.copyOf(results));
        }
        @Override public CompletableFuture<Set<EntityChunkKey>> cleanStoredEntities(
                PreparedMinecraftState target) {
            return CompletableFuture.completedFuture(Set.copyOf(target.entityKeys()));
        }
        @Override public DimensionFreeze.Lease suppressEntityLoads(
                Set<EntityChunkKey> keys) {
            return () -> { };
        }
        @Override public List<Integer> blockEntityIndexes(SectionKey key) { return List.of(); }
        @Override public void removeBlockEntity(SectionKey key, int localIndex) { }
        @Override public void loadBlockEntity(
                SectionKey key, int localIndex, net.minecraft.nbt.CompoundTag nbt) { }
        @Override public SectionBlob captureSection(SectionKey key) { return captured; }
        @Override public List<UUID> durableEntityIds(EntityChunkKey key) {
            startedEntityChunks.add(key);
            return entityIdsByChunk.getOrDefault(key, entityIds);
        }
        @Override public void removeEntity(UUID id) { }
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
            playerSpawnWrites++;
            playerSpawns = Map.copyOf(spawns);
        }
        @Override public boolean matchesPlayerSpawns(Map<UUID, PlayerSpawn> spawns) {
            playerSpawnMatches++;
            return playerSpawns.equals(spawns);
        }
    }

    private static final class ManualPersistence implements WorldPersistenceSession {
        private boolean complete;
        private List<ChunkCoordinate> accepted = List.of();
        private IOException failure;
        private Timings timings = Timings.EMPTY;
        private int closeCalls;
        @Override public boolean advanceUntil(long deadlineNanos) throws IOException {
            if (failure != null) {
                throw failure;
            }
            return complete;
        }
        @Override public List<ChunkCoordinate> drainAcceptedSnapshotChunks() {
            List<ChunkCoordinate> drained = accepted;
            accepted = List.of();
            return drained;
        }
        @Override public Timings timings() { return timings; }
        @Override public void close() { closeCalls++; }
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

    private static final class ImmediateChunkAccess implements ChunkLoadAccess {
        private final List<ChunkCoordinate> retained = new ArrayList<>();
        private final List<ChunkCoordinate> released = new ArrayList<>();
        private int active;
        private int peakRetained;

        @Override public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
            retained.add(chunk);
            peakRetained = Math.max(peakRetained, ++active);
            return CompletableFuture.completedFuture(null);
        }
        @Override public boolean isReady(ChunkCoordinate chunk) { return true; }
        @Override public void release(ChunkCoordinate chunk) {
            released.add(chunk);
            active--;
        }
    }

    private static final class ControlledChunkAccess implements ChunkLoadAccess {
        private final Map<ChunkCoordinate, CompletableFuture<Void>> loads =
                new LinkedHashMap<>();
        private final Set<ChunkCoordinate> ready = new java.util.HashSet<>();
        private final List<ChunkCoordinate> retained = new ArrayList<>();
        private final List<ChunkCoordinate> released = new ArrayList<>();

        @Override
        public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
            retained.add(chunk);
            return loads.computeIfAbsent(chunk, ignored -> new CompletableFuture<>());
        }

        @Override
        public boolean isReady(ChunkCoordinate chunk) {
            return ready.contains(chunk);
        }

        @Override
        public void release(ChunkCoordinate chunk) {
            released.add(chunk);
        }

        private void complete(ChunkCoordinate chunk) {
            ready.add(chunk);
            loads.get(chunk).complete(null);
        }

        private int active() {
            return retained.size() - released.size();
        }
    }
}
