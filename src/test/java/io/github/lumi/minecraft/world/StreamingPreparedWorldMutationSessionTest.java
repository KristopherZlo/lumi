package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.PlayerSpawn;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.service.RestorePlanMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StreamingPreparedWorldMutationSessionTest {
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
    void boundsOnePreparationSlabToEstimatedOneHundredTwentyEightMib() {
        List<SectionKey> keys = new ArrayList<>();
        for (int section = 0; section < 1_100; section++) {
            keys.add(new SectionKey(0, section, 0));
        }

        assertEquals(1_024, StreamingPreparedWorldMutationSession.slabEnd(keys, 0));
        assertEquals(1_100, StreamingPreparedWorldMutationSession.slabEnd(keys, 1_024));
        assertEquals(1_024, StreamingPreparedWorldMutationSession.windowEnd(
                keys, 0, 1_024));
    }

    @Test
    void boundsEntityTicketsToThirtyTwoChunks() {
        assertEquals(32, StreamingPreparedWorldMutationSession.entityBatchEnd(40, 0));
        assertEquals(40, StreamingPreparedWorldMutationSession.entityBatchEnd(40, 32));
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

        try (var session = session(plan(keys, section), world, background)) {
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(1, background.submitted);

            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(32), world.persistenceWindowSizes);
            assertEquals(1, background.submitted);

            world.persistence.getFirst().complete = true;
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(32, 8), world.persistenceWindowSizes);
            assertEquals(1, world.persistence.getFirst().closeCalls);
            assertEquals(1, background.submitted);

            world.persistence.getLast().complete = true;
            assertTrue(session.applyUntil(Long.MAX_VALUE));
            assertEquals(1, background.submitted);
        }
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
            assertEquals(List.of(1_024), world.persistenceWindowSizes);
            assertEquals(1, background.submitted);

            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(1, background.submitted);

            world.persistence.getFirst().complete = true;
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(2, background.submitted);
            assertEquals(1, background.pending());
            assertEquals(1, world.persistence.getFirst().closeCalls);
            assertEquals(List.of(1_024), world.persistenceWindowSizes);

            background.runNext();
            assertFalse(session.applyUntil(Long.MAX_VALUE));
            assertEquals(List.of(1_024, 1), world.persistenceWindowSizes);
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
                state, state, Map.of(), Map.of(), List.of(key), List.of());
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
        return new StreamingPreparedWorldMutationSession(
                plan,
                new MinecraftRestorePreparation(
                        new MinecraftBlockStateDecoder(BuiltInRegistries.BLOCK),
                        new MinecraftEntityStateDecoder(BuiltInRegistries.ENTITY_TYPE)),
                world, background, () -> null);
    }

    private static PreparedMinecraftPlanState plan(
            List<SectionKey> keys, SectionBlob section) {
        Map<SectionKey, SectionBlob> sections = new LinkedHashMap<>();
        keys.forEach(key -> sections.put(key, section));
        var state = new WorldStateApply.State(sections, Map.of());
        return new PreparedMinecraftPlanState(
                state, state, Map.of(), Map.of(), keys, List.of());
    }

    private static SectionBlob stoneSection() {
        return new SectionBlob(
                Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:stone"),
                Map.of());
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

    private static final class FakeWorld implements PreparedWorldAccess {
        private final SectionBlob captured;
        private final List<ManualPersistence> persistence = new ArrayList<>();
        private final List<Integer> persistenceWindowSizes = new ArrayList<>();

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
            persistenceWindowSizes.add(target.sectionKeys().size());
            ManualPersistence next = new ManualPersistence();
            persistence.add(next);
            return next;
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
            return List.of();
        }
        @Override public void removeEntity(EntityChunkKey key, UUID id) { }
        @Override public void addEntity(EntityChunkKey key, DecodedEntity entity) { }
        @Override public EntityChunkBlob captureEntities(EntityChunkKey key) {
            return new EntityChunkBlob(List.of());
        }
        @Override public void applyPlayerSpawns(Map<UUID, PlayerSpawn> spawns) { }
        @Override public boolean matchesPlayerSpawns(Map<UUID, PlayerSpawn> spawns) {
            return true;
        }
    }

    private static final class ManualPersistence implements WorldPersistenceSession {
        private boolean complete;
        private int closeCalls;

        @Override
        public boolean advanceUntil(long deadlineNanos) {
            return complete;
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
