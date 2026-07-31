package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CanonicalNbt;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.EntityState;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.RestorePlanMap;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StreamingPreparedWorldMutationSessionTest {
    @Test
    void boundsPersistedReadbackWindows() {
        assertEquals(32, MinecraftPersistedBatchVerifier.readBatchEnd(40, 0, 32));
        assertEquals(40, MinecraftPersistedBatchVerifier.readBatchEnd(40, 32, 32));
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void boundsOneDurabilityWindowToThirtyTwoChunks() {
        List<SectionKey> keys = new ArrayList<>();
        for (int chunk = 0; chunk < 40; chunk++) {
            keys.add(new SectionKey(chunk, 0, 0));
        }

        assertEquals(32, StreamingPreparedWorldMutationSession.windowEnd(
                keys, 0, keys.size()));
        assertEquals(40, StreamingPreparedWorldMutationSession.windowEnd(
                keys, 32, keys.size()));
    }

    @Test
    void capsOnePreparationSlabAtOneThousandTwentyFourSections() {
        List<SectionKey> keys = new ArrayList<>();
        for (int section = 0; section < 1_100; section++) {
            keys.add(new SectionKey(0, section, 0));
        }

        assertEquals(1_024,
                StreamingPreparedWorldMutationSession.MAX_SECTIONS_PER_SLAB);
        assertEquals(1_024, StreamingPreparedWorldMutationSession.windowEnd(
                keys, 0, 1_024));
    }

    @Test
    void boundsEntityTicketsToThirtyTwoChunks() {
        assertEquals(32, StreamingPreparedWorldMutationSession.entityBatchEnd(40, 0));
        assertEquals(40, StreamingPreparedWorldMutationSession.entityBatchEnd(40, 32));
    }

    @Test
    void removesReplacedEntitiesBeforeSectionsAndStillAppliesFinalState()
            throws Exception {
        List<EntityChunkKey> keys = entityKeys(3);
        SectionKey sectionKey = new SectionKey(3, 0, 0);
        SectionBlob section = stoneSection();
        PreparedMinecraftPlanState entities = entityPlan(keys);
        var source = new WorldStateApply.State(
                Map.of(sectionKey, section), entities.source().entities());
        var base = new WorldStateApply.State(
                Map.of(sectionKey, section), entities.base().entities());
        var mixedPlan = new PreparedMinecraftPlanState(
                source, base, entities.entities(), entities.baseEntities(),
                List.of(sectionKey), keys, entities.cleanupEntityIds());
        FakeWorld world = new FakeWorld(section);
        world.cleanAllStoredEntities = false;
        world.directlyCleanedEntities = Set.of(keys.getFirst());
        world.durableEntities = Map.of(
                keys.get(1), List.of(new UUID(1, 1)),
                keys.get(2), List.of(new UUID(2, 2)));
        RecordingChunkAccess chunks = new RecordingChunkAccess();
        CompletableFuture<Void> secondCleanupLoad = new CompletableFuture<>();
        chunks.loads.put(
                new ChunkCoordinate(keys.get(2).chunkX(), keys.get(2).chunkZ()),
                secondCleanupLoad);
        List<ChunkLoadAccess.Readiness> requested = new ArrayList<>();
        ControlledExecutor background = new ControlledExecutor();

        var session = session(
                mixedPlan, world, background, readiness -> {
                    requested.add(readiness);
                    return new ChunkLoadSession(chunks, () -> 0L);
                });

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(keys, world.requestedEntityCleanup);
        assertTrue(world.requestedEntityCleanupTarget.source().entities().values()
                .stream().allMatch(chunk -> chunk.entities().isEmpty()));
        assertTrue(world.startedEntityChunks.isEmpty());
        assertTrue(world.removedEntityChunks.isEmpty());
        assertEquals(2, chunks.peak);
        assertEquals(2, chunks.active);
        assertTrue(world.persistence.isEmpty());
        assertEquals(1, background.submitted);
        assertEquals(1, background.pending());
        assertEquals(Set.copyOf(keys), world.suppressedEntityLoads);
        assertEquals(0, world.entitySuppressionReleases);
        assertEquals(List.of(
                ChunkLoadAccess.Readiness.TERRAIN_AND_ENTITIES), requested);

        background.runNext();
        assertEquals(0, background.pending());
        assertTrue(world.persistence.isEmpty());
        assertTrue(world.startedEntityChunks.isEmpty());

        secondCleanupLoad.complete(null);
        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(keys.subList(1, 3), world.startedEntityChunks);
        assertEquals(keys.subList(1, 3), world.removedEntityChunks);
        assertEquals(1, world.persistence.size());

        world.persistence.getFirst().complete = true;
        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(1, background.submitted);
        assertEquals(0, background.pending());
        assertTrue(world.suppressedEntityLoads.isEmpty());
        assertEquals(1, world.entitySuppressionReleases);
        world.persistence.getLast().complete = true;
        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(
                keys.get(1), keys.get(2),
                keys.get(0), keys.get(1), keys.get(2)),
                world.startedEntityChunks);

        session.close();
        session.close();
        assertEquals(0, chunks.active);
        assertEquals(1, world.entitySuppressionReleases);
        assertFalse(world.finalEntityAddedWhileSuppressed);
    }

    @Test
    void boundsLargeFallbackCleanupDurabilityBatchesToThirtyTwoChunks()
            throws Exception {
        List<EntityChunkKey> keys = entityKeys(33);
        UUID duplicate = new UUID(7, 7);
        FakeWorld world = new FakeWorld(null);
        world.cleanAllStoredEntities = false;
        world.durableEntities = Map.of(
                keys.getFirst(), List.of(duplicate),
                keys.getLast(), List.of(duplicate));
        world.indexedEntities = new HashSet<>(Set.of(duplicate));
        RecordingChunkAccess chunks = new RecordingChunkAccess();
        var session = session(
                legacyDuplicatePlan(keys, duplicate), world, new ControlledExecutor(),
                ignored -> new ChunkLoadSession(chunks, () -> 0L));

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(32), world.requestedEntityCleanupSizes);
        assertTrue(world.persistence.isEmpty());

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(List.of(32, 1), world.requestedEntityCleanupSizes);
        assertEquals(keys.subList(0, 32), world.startedEntityChunks);
        assertEquals(1, world.persistence.size());
        assertEquals(32, chunks.peak);
        assertTrue(world.globalRemovalBeforeFirstEntityChunk);
        assertFalse(world.duplicateEntityLoad);
        assertTrue(world.addedEntities.isEmpty());

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(keys.subList(0, 32), world.startedEntityChunks);
        world.persistence.getFirst().complete = true;
        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(keys, world.startedEntityChunks);
        assertEquals(2, world.persistence.size());
        assertEquals(1, chunks.active);
        assertFalse(world.duplicateEntityLoad);
        assertTrue(world.addedEntities.isEmpty());

        session.close();
        assertEquals(0, chunks.active);
    }

    @Test
    void preparesFortyChunksOnceAndPersistsTwoWindows() throws Exception {
        List<SectionKey> keys = new ArrayList<>();
        for (int chunk = 0; chunk < 40; chunk++) {
            keys.add(new SectionKey(chunk, 0, 0));
        }
        SectionBlob section = stoneSection();
        ControlledExecutor background = new ControlledExecutor();
        FakeWorld world = new FakeWorld(section);
        world.directlyStored = Set.of(
                new ChunkCoordinate(0, 0), new ChunkCoordinate(32, 0));

        try (var session = session(plan(keys, section), world, background)) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(1, background.submitted);

            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(32), world.persistenceWindowSizes());
            assertEquals(PreparedWorldMutationSession.PersistenceMode.STAGE,
                    world.persistenceCalls.getFirst().mode());
            assertEquals(32,
                    world.persistenceCalls.getFirst().verificationSections());
            assertEquals(Set.of(new ChunkCoordinate(0, 0)),
                    world.persistenceCalls.getFirst().alreadyDurable());
            assertEquals(1, background.submitted);

            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(1, world.persistenceCalls.size());

            world.persistence.getFirst().complete = true;
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(32, 8), world.persistenceWindowSizes());
            PersistenceCall commit = world.persistenceCalls.getLast();
            assertEquals(PreparedWorldMutationSession.PersistenceMode.SLAB_END,
                    commit.mode());
            assertEquals(40, commit.verificationSections());
            assertEquals(Set.of(
                    new ChunkCoordinate(0, 0), new ChunkCoordinate(32, 0)),
                    commit.alreadyDurable());
            assertEquals(1, world.persistence.getFirst().closeCalls);
            assertEquals(1, background.submitted);

            world.persistence.getLast().complete = true;
            assertTrue(session.applyUntil(Long.MAX_VALUE));
            assertEquals(1, background.submitted);
            assertEquals(1, world.persistence.getLast().closeCalls);
            assertTrue(session.statistics().batchPreparationNanos() > 0);
        }
    }

    @Test
    void releasesFirstWindowTicketsBeforeRetainingTheSecondWindow() throws Exception {
        List<SectionKey> keys = new ArrayList<>();
        for (int chunk = 0; chunk < 40; chunk++) {
            keys.add(new SectionKey(chunk, 0, 0));
        }
        SectionBlob section = stoneSection();
        ControlledExecutor background = new ControlledExecutor();
        FakeWorld world = new FakeWorld(section);
        RecordingChunkAccess chunks = new RecordingChunkAccess();
        List<ChunkLoadAccess.Readiness> requested = new ArrayList<>();

        try (var session = session(
                plan(keys, section), world, background, readiness -> {
                    requested.add(readiness);
                    return new ChunkLoadSession(chunks, () -> 0L);
                })) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(0, chunks.active);
            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(32, chunks.active);
            assertEquals(List.of(ChunkLoadAccess.Readiness.TERRAIN), requested);

            world.persistence.getFirst().complete = true;
            assertFalse(session.applyUntil(Long.MAX_VALUE));

            assertEquals(8, chunks.active);
            assertEquals(0, chunks.activeBeforeRetain.get(32));
            assertEquals(32, chunks.peak);
            assertEquals(List.of(
                    ChunkLoadAccess.Readiness.TERRAIN,
                    ChunkLoadAccess.Readiness.TERRAIN), requested);
        }
        assertEquals(0, chunks.active);
        assertEquals(1, world.persistence.getLast().closeCalls);
    }

    @Test
    void retainsNeighborTerrainOnlyForLightChangingWindows() throws Exception {
        SectionKey key = new SectionKey(0, 0, 0);
        SectionBlob target = glowstoneSection();
        var source = new WorldStateApply.State(Map.of(key, target), Map.of());
        var base = new WorldStateApply.State(Map.of(key, stoneSection()), Map.of());
        var plan = new PreparedMinecraftPlanState(
                source, base, Map.of(), Map.of(), List.of(key), List.of(), Set.of());
        ControlledExecutor background = new ControlledExecutor();
        List<ChunkLoadAccess.Readiness> requested = new ArrayList<>();

        try (var session = session(
                plan, new FakeWorld(target), background, readiness -> {
                    requested.add(readiness);
                    return new ChunkLoadSession(
                            new RecordingChunkAccess(), () -> 0L);
                })) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
        }

        assertEquals(List.of(
                ChunkLoadAccess.Readiness.TERRAIN_WITH_NEIGHBORS), requested);
    }

    @Test
    void queuesNextSlabOnlyAfterTheCurrentSlabIsDurable() throws Exception {
        List<SectionKey> keys = new ArrayList<>();
        for (int sectionY = 0; sectionY < 1_025; sectionY++) {
            keys.add(new SectionKey(0, sectionY, 0));
        }
        SectionBlob section = stoneSection();
        ControlledExecutor background = new ControlledExecutor();
        FakeWorld world = new FakeWorld(section);

        try (var session = session(plan(keys, section), world, background)) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(1_024), world.persistenceWindowSizes());
            assertEquals(PreparedWorldMutationSession.PersistenceMode.SLAB_END,
                    world.persistenceCalls.getFirst().mode());
            assertEquals(1, background.submitted);

            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(1, background.submitted);

            world.persistence.getFirst().complete = true;
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(2, background.submitted);
            assertEquals(1, background.pending());
            assertEquals(1, world.persistence.getFirst().closeCalls);
            assertEquals(List.of(1_024), world.persistenceWindowSizes());

            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(1_024, 1), world.persistenceWindowSizes());
        }
    }

    @Test
    void splitsDistinctNbtHeavySectionsAtTheResidentBudget() throws Exception {
        List<SectionKey> keys = List.of(
                new SectionKey(0, 0, 0), new SectionKey(0, 1, 0));
        Map<SectionKey, SectionBlob> sections = new LinkedHashMap<>();
        sections.put(keys.get(0), chestSection(2 * 1024 * 1024));
        sections.put(keys.get(1), chestSection(2 * 1024 * 1024));
        ControlledExecutor background = new ControlledExecutor();
        FakeWorld world = new FakeWorld(sections.get(keys.getFirst()));

        try (var session = session(plan(keys, sections), world, background)) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(1), world.persistenceWindowSizes());

            world.persistence.getFirst().complete = true;
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(2, background.submitted);
            assertEquals(1, background.pending());
        }
    }

    @Test
    void countsOneSharedNbtHeavyTargetOnlyOnce() throws Exception {
        List<SectionKey> keys = List.of(
                new SectionKey(0, 0, 0), new SectionKey(0, 1, 0));
        SectionBlob shared = chestSection(2 * 1024 * 1024);
        ControlledExecutor background = new ControlledExecutor();
        FakeWorld world = new FakeWorld(shared);

        try (var session = session(plan(keys, shared), world, background)) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));

            assertEquals(List.of(2), world.persistenceWindowSizes());
            assertEquals(1, background.submitted);
        }
    }

    @Test
    void preparesOneOversizedSectionSoTheRestoreCanProgress() throws Exception {
        List<SectionKey> keys = List.of(new SectionKey(0, 0, 0));
        SectionBlob section = chestSection(5 * 1024 * 1024);
        ControlledExecutor background = new ControlledExecutor();
        FakeWorld world = new FakeWorld(section);

        try (var session = session(plan(keys, section), world, background)) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));

            assertEquals(List.of(1), world.persistenceWindowSizes());
        }
    }

    @Test
    void closeCancelsQueuedPreparationWithoutReadingThePlan() throws Exception {
        SectionKey key = new SectionKey(0, 0, 0);
        SectionBlob section = stoneSection();
        AtomicInteger reads = new AtomicInteger();
        Map<SectionKey, SectionBlob> sections = new RestorePlanMap<>(
                Set.of(key), ignored -> {
                    reads.incrementAndGet();
                    return section;
                });
        var state = new WorldStateApply.State(sections, Map.of());
        var plan = new PreparedMinecraftPlanState(
                state, state, Map.of(), Map.of(), List.of(key), List.of(),
                Set.of());
        ControlledExecutor background = new ControlledExecutor();
        var session = session(plan, new FakeWorld(section), background);

        assertFalse(session.applyUntil(Long.MAX_VALUE));
        assertEquals(1, background.pending());
        session.close();
        session.close();
        background.runNext();

        assertEquals(0, reads.get());
        assertFalse(session.applyUntil(Long.MAX_VALUE));
    }

    private static StreamingPreparedWorldMutationSession session(
            PreparedMinecraftPlanState plan,
            FakeWorld world,
            ControlledExecutor background) {
        return session(plan, world, background, ignored -> null);
    }

    private static StreamingPreparedWorldMutationSession session(
            PreparedMinecraftPlanState plan,
            FakeWorld world,
            ControlledExecutor background,
            Function<ChunkLoadAccess.Readiness, ChunkLoadSession> chunkLoads) {
        return new StreamingPreparedWorldMutationSession(
                plan,
                new MinecraftRestorePreparation(
                        new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                        new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE)),
                world, background, chunkLoads);
    }

    private static void startFirstMutation(
            StreamingPreparedWorldMutationSession session,
            FakeWorld world) throws IOException {
        while (world.persistence.isEmpty()) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
        }
    }

    private static PreparedMinecraftPlanState plan(
            List<SectionKey> keys, SectionBlob section) {
        Map<SectionKey, SectionBlob> sections = new LinkedHashMap<>();
        keys.forEach(key -> sections.put(key, section));
        return plan(keys, sections);
    }

    private static PreparedMinecraftPlanState plan(
            List<SectionKey> keys, Map<SectionKey, SectionBlob> sections) {
        var state = new WorldStateApply.State(sections, Map.of());
        return new PreparedMinecraftPlanState(
                state, state, Map.of(), Map.of(), keys, List.of(), Set.of());
    }

    private static PreparedMinecraftPlanState entityPlan(List<EntityChunkKey> keys) {
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        Map<EntityChunkKey, EntityChunkBlob> entities = new LinkedHashMap<>();
        Map<EntityChunkKey, EntityChunkBlob> baseEntities = new LinkedHashMap<>();
        Map<EntityChunkKey, DecodedEntityChunk> decoded = new LinkedHashMap<>();
        Set<UUID> cleanupIds = new HashSet<>();
        keys.forEach(key -> {
            entities.put(key, empty);
            UUID id = new UUID(key.chunkX(), key.chunkZ() + 1L);
            cleanupIds.add(id);
            baseEntities.put(key, new EntityChunkBlob(List.of(new EntityState(
                    id, "minecraft:rabbit", new CanonicalNbt(new byte[] {1})))));
            decoded.put(key, new DecodedEntityChunk(List.of()));
        });
        var source = new WorldStateApply.State(Map.of(), entities);
        var base = new WorldStateApply.State(Map.of(), baseEntities);
        return new PreparedMinecraftPlanState(
                source, base, decoded, decoded, List.of(), keys, cleanupIds);
    }

    private static PreparedMinecraftPlanState legacyDuplicatePlan(
            List<EntityChunkKey> keys, UUID id) throws IOException {
        EntityChunkBlob empty = new EntityChunkBlob(List.of());
        EntityChunkBlob canonical = new EntityChunkBlob(List.of(new EntityState(
                id, "minecraft:rabbit",
                MinecraftNbtCodec.encode(new CompoundTag()))));
        DecodedEntityChunk decodedEmpty = new DecodedEntityChunk(List.of());
        DecodedEntityChunk decodedCanonical = new MinecraftEntityStateDecoder(
                BuiltInRegistries.ENTITY_TYPE).decode(canonical);
        Map<EntityChunkKey, EntityChunkBlob> entities = new LinkedHashMap<>();
        Map<EntityChunkKey, DecodedEntityChunk> decoded = new LinkedHashMap<>();
        keys.forEach(key -> {
            boolean selected = key.equals(keys.getFirst());
            entities.put(key, selected ? canonical : empty);
            decoded.put(key, selected ? decodedCanonical : decodedEmpty);
        });
        var state = new WorldStateApply.State(Map.of(), entities);
        return new PreparedMinecraftPlanState(
                state, state, decoded, decoded, List.of(), keys, Set.of(id));
    }

    private static List<EntityChunkKey> entityKeys(int chunks) {
        List<EntityChunkKey> keys = new ArrayList<>();
        for (int chunk = 0; chunk < chunks; chunk++) {
            keys.add(new EntityChunkKey(chunk, 0));
        }
        return keys;
    }

    private static SectionBlob stoneSection() {
        return new SectionBlob(
                Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:stone"),
                Map.of());
    }

    private static SectionBlob glowstoneSection() {
        return new SectionBlob(
                Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:glowstone"),
                Map.of());
    }

    private static SectionBlob chestSection(int payloadBytes) throws Exception {
        var states = new ArrayList<>(
                Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:stone"));
        states.set(0, "minecraft:chest");
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putByteArray("lumi_test_payload", new byte[payloadBytes]);
        return new SectionBlob(
                states, Map.of(0, MinecraftNbtCodec.encode(chest)));
    }

    private static final class ControlledExecutor implements Executor {
        private final ArrayDeque<Runnable> queued = new ArrayDeque<>();
        private int submitted;

        @Override
        public void execute(Runnable task) {
            submitted++;
            queued.addLast(task);
        }

        private void runNext() {
            queued.removeFirst().run();
        }

        private int pending() {
            return queued.size();
        }
    }

    private static final class RecordingChunkAccess implements ChunkLoadAccess {
        private final List<Integer> activeBeforeRetain = new ArrayList<>();
        private final Map<ChunkCoordinate, CompletableFuture<Void>> loads =
                new LinkedHashMap<>();
        private int active;
        private int peak;

        @Override
        public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
            activeBeforeRetain.add(active);
            peak = Math.max(peak, ++active);
            return loads.getOrDefault(
                    chunk, CompletableFuture.completedFuture(null));
        }

        @Override public boolean isReady(ChunkCoordinate chunk) {
            return true;
        }

        @Override public void startLoading() { }

        @Override public void release(ChunkCoordinate chunk) {
            active--;
        }
    }

    private static final class FakeWorld implements PreparedWorldAccess {
        private final SectionBlob captured;
        private final List<ManualPersistence> persistence = new ArrayList<>();
        private final List<PersistenceCall> persistenceCalls = new ArrayList<>();
        private final List<EntityChunkKey> startedEntityChunks = new ArrayList<>();
        private final List<EntityChunkKey> removedEntityChunks = new ArrayList<>();
        private final List<UUID> globallyRemovedEntityIds = new ArrayList<>();
        private final List<DecodedEntity> addedEntities = new ArrayList<>();
        private final List<EntityChunkKey> requestedEntityCleanup = new ArrayList<>();
        private final List<Integer> requestedEntityCleanupSizes = new ArrayList<>();
        private Set<ChunkCoordinate> directlyStored = Set.of();
        private boolean cleanAllStoredEntities = true;
        private Set<EntityChunkKey> directlyCleanedEntities = Set.of();
        private Map<EntityChunkKey, List<UUID>> durableEntities = Map.of();
        private Set<UUID> indexedEntities;
        private boolean globalRemovalBeforeFirstEntityChunk;
        private boolean duplicateEntityLoad;
        private boolean finalEntityAddedWhileSuppressed;
        private int entitySuppressionReleases;
        private Set<EntityChunkKey> suppressedEntityLoads = Set.of();
        private PreparedMinecraftState requestedEntityCleanupTarget;

        private FakeWorld(SectionBlob captured) {
            this.captured = captured;
        }

        @Override
        public SectionApplyResult applySection(SectionKey key, DecodedSection section) {
            return new SectionApplyResult(key, new short[0], 0);
        }

        @Override
        public ChunkSyncResult finishChunk(
                ChunkCoordinate chunk,
                List<SectionApplyResult> sections,
                boolean blockEntitiesChanged) {
            return ChunkSyncResult.NONE;
        }

        @Override
        public WorldPersistenceSession beginPersistence(
                PreparedMinecraftState target,
                Set<ChunkCoordinate> alreadyDurable,
                boolean playerSpawnsIncluded) {
            return persistence(PreparedWorldMutationSession.PersistenceMode.COMPLETE,
                    target, target.source(), alreadyDurable);
        }

        @Override
        public WorldPersistenceSession beginPersistenceStage(
                PreparedMinecraftState writeTarget,
                Set<ChunkCoordinate> alreadyDurable) {
            return persistence(PreparedWorldMutationSession.PersistenceMode.STAGE,
                    writeTarget, writeTarget.source(), alreadyDurable);
        }

        @Override
        public WorldPersistenceSession beginPersistenceCommit(
                PreparedMinecraftState writeTarget,
                WorldStateApply.State verificationTarget,
                Set<ChunkCoordinate> alreadyDurable) {
            return persistence(PreparedWorldMutationSession.PersistenceMode.SLAB_END,
                    writeTarget, verificationTarget, alreadyDurable);
        }

        private WorldPersistenceSession persistence(
                PreparedWorldMutationSession.PersistenceMode mode,
                PreparedMinecraftState writeTarget,
                WorldStateApply.State verificationTarget,
                Set<ChunkCoordinate> alreadyDurable) {
            persistenceCalls.add(new PersistenceCall(
                    mode, writeTarget.sectionKeys().size(),
                    verificationTarget.sections().size(), alreadyDurable));
            ManualPersistence next = new ManualPersistence();
            persistence.add(next);
            return next;
        }

        private List<Integer> persistenceWindowSizes() {
            return persistenceCalls.stream().map(PersistenceCall::writeSections).toList();
        }

        @Override
        public CompletableFuture<Map<ChunkCoordinate, StoredChunkApplyResult>>
                applyStoredChunks(
                        Map<ChunkCoordinate, Map<SectionKey, DecodedSection>> chunks,
                        Set<ChunkCoordinate> entityChunks) {
            Map<ChunkCoordinate, StoredChunkApplyResult> results = new LinkedHashMap<>();
            chunks.keySet().forEach(chunk -> results.put(chunk,
                    directlyStored.contains(chunk)
                            ? StoredChunkApplyResult.APPLIED
                            : StoredChunkApplyResult.FALLBACK));
            return CompletableFuture.completedFuture(Map.copyOf(results));
        }

        @Override
        public CompletableFuture<Set<EntityChunkKey>> cleanStoredEntities(
                PreparedMinecraftState target) {
            requestedEntityCleanupTarget = target;
            requestedEntityCleanup.addAll(target.entityKeys());
            requestedEntityCleanupSizes.add(target.entityKeys().size());
            return CompletableFuture.completedFuture(target.entityKeys().stream()
                    .filter(key -> cleanAllStoredEntities
                            || directlyCleanedEntities.contains(key))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }

        @Override
        public DimensionFreeze.Lease suppressEntityLoads(Set<EntityChunkKey> keys) {
            if (!suppressedEntityLoads.isEmpty()) {
                throw new IllegalStateException("Entity loads are already suppressed");
            }
            suppressedEntityLoads = Set.copyOf(keys);
            return () -> {
                if (suppressedEntityLoads.isEmpty()) {
                    throw new IllegalStateException("Entity loads are not suppressed");
                }
                suppressedEntityLoads = Set.of();
                entitySuppressionReleases++;
            };
        }

        @Override public List<Integer> blockEntityIndexes(SectionKey key) {
            return List.of();
        }
        @Override public void removeBlockEntity(SectionKey key, int localIndex) { }
        @Override public void loadBlockEntity(
                SectionKey key, int localIndex, CompoundTag nbt) { }
        @Override public SectionBlob captureSection(SectionKey key) {
            return captured;
        }
        @Override public List<UUID> durableEntityIds(EntityChunkKey key) {
            if (startedEntityChunks.isEmpty()) {
                globalRemovalBeforeFirstEntityChunk =
                        !globallyRemovedEntityIds.isEmpty();
            }
            startedEntityChunks.add(key);
            List<UUID> ids = durableEntities.getOrDefault(key, List.of());
            if (indexedEntities != null) {
                ids.forEach(id -> duplicateEntityLoad |= !indexedEntities.add(id));
            }
            return ids;
        }
        @Override public void removeEntity(UUID id) {
            globallyRemovedEntityIds.add(id);
            if (indexedEntities != null) {
                indexedEntities.remove(id);
            }
        }
        @Override public void removeEntity(EntityChunkKey key, UUID id) {
            removedEntityChunks.add(key);
            if (indexedEntities != null) {
                indexedEntities.remove(id);
            }
        }
        @Override public void addEntity(EntityChunkKey key, DecodedEntity entity) {
            finalEntityAddedWhileSuppressed |= !suppressedEntityLoads.isEmpty();
            addedEntities.add(entity);
            if (indexedEntities != null) {
                duplicateEntityLoad |= !indexedEntities.add(entity.id());
            }
        }
        @Override public EntityChunkBlob captureEntities(EntityChunkKey key) {
            return new EntityChunkBlob(List.of());
        }
        @Override public void applyPlayerSpawns(Map<UUID, PlayerSpawn> spawns) { }
        @Override public boolean matchesPlayerSpawns(Map<UUID, PlayerSpawn> spawns) {
            return true;
        }
    }

    private record PersistenceCall(
            PreparedWorldMutationSession.PersistenceMode mode,
            int writeSections,
            int verificationSections,
            Set<ChunkCoordinate> alreadyDurable) {
        private PersistenceCall {
            alreadyDurable = Set.copyOf(alreadyDurable);
        }
    }

    private static final class ManualPersistence implements WorldPersistenceSession {
        private boolean complete;
        private List<ChunkCoordinate> accepted = List.of();
        private int closeCalls;

        @Override
        public boolean advanceUntil(long deadlineNanos) {
            return complete;
        }

        @Override
        public List<ChunkCoordinate> drainAcceptedSnapshotChunks() {
            List<ChunkCoordinate> drained = accepted;
            accepted = List.of();
            return drained;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
