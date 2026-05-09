package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSessionStateTest {

    @Test
    void rootChunksDefineEnvelopeWithoutImmediateDirtyWork() {
        CaptureSessionState state = CaptureSessionState.create(buffer());

        assertTrue(state.addRootChunk(new ChunkPoint(10, -4)));
        assertFalse(state.addRootChunk(new ChunkPoint(10, -4)));
        assertEquals(List.of(new ChunkPoint(10, -4)), state.rootChunks());
        assertTrue(state.isRootChunk(new ChunkPoint(10, -4)));
        assertFalse(state.isRootChunk(new ChunkPoint(10, -3)));
        assertTrue(state.isWithinStabilizationEnvelope(new ChunkPoint(11, -3)));
        assertFalse(state.isWithinStabilizationEnvelope(new ChunkPoint(12, -4)));
        assertTrue(state.dirtyChunks().isEmpty());
        assertFalse(state.hasPendingReconciliation());
    }

    @Test
    void resumeSeedsRootsFromExistingDraftChunks() {
        Instant now = Instant.parse("2026-04-20T10:15:30Z");
        TrackedChangeBuffer buffer = buffer();
        buffer.addChange(new StoredBlockChange(
                new BlockPoint(1, 70, 1),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        ), now);
        buffer.addChange(new StoredBlockChange(
                new BlockPoint(34, 70, 1),
                payload("minecraft:stone"),
                payload("minecraft:gold_block")
        ), now.plusSeconds(1));

        CaptureSessionState state = CaptureSessionState.resume(buffer);

        assertEquals(List.of(new ChunkPoint(0, 0), new ChunkPoint(2, 0)), state.rootChunks());
        assertFalse(state.hasPendingReconciliation());
        assertTrue(state.isWithinStabilizationEnvelope(new ChunkPoint(1, 0)));
    }

    @Test
    void currentChunkChangesReflectLiveBufferStateAfterSessionStart() {
        Instant now = Instant.parse("2026-04-20T10:15:30Z");
        TrackedChangeBuffer buffer = buffer();
        CaptureSessionState state = CaptureSessionState.create(buffer);
        ChunkPoint chunk = new ChunkPoint(0, 0);

        buffer.addChange(new StoredBlockChange(
                new BlockPoint(1, 70, 1),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        ), now);
        buffer.addChange(new StoredBlockChange(
                new BlockPoint(1, 70, 1),
                payload("minecraft:dirt"),
                payload("minecraft:gold_block")
        ), now.plusSeconds(1));

        assertTrue(state.startingChunkChanges(List.of(chunk)).isEmpty());

        List<StoredBlockChange> currentChanges = state.currentChunkChanges(List.of(chunk));
        assertEquals(1, currentChanges.size());
        assertEquals("minecraft:stone", currentChanges.getFirst().oldValue().blockId());
        assertEquals("minecraft:gold_block", currentChanges.getFirst().newValue().blockId());
    }

    @Test
    void reconciliationDrainAndTrackedFallingEntitiesRemainCoalesced() {
        CaptureSessionState state = CaptureSessionState.create(buffer());
        state.addRootChunk(new ChunkPoint(0, 0));
        CaptureSessionState.DeferredActionContext deferredAction =
                new CaptureSessionState.DeferredActionContext("action-1", "builder", true);
        state.markDirtyChunk(new ChunkPoint(0, 0), deferredAction);
        state.markDirtyChunk(new ChunkPoint(1, 0));

        assertEquals(List.of(new ChunkPoint(0, 0), new ChunkPoint(1, 0)), state.pendingReconcileChunks());

        UUID entityId = UUID.randomUUID();
        assertTrue(state.trackFallingEntity(entityId));
        assertFalse(state.trackFallingEntity(entityId));
        assertTrue(state.isTrackedFallingEntity(entityId));
        assertTrue(state.beginReconciliation());
        assertFalse(state.beginReconciliation());

        List<ChunkPoint> drained = state.drainPendingReconcileChunks();
        assertEquals(2, drained.size());
        assertEquals(
                deferredAction,
                state.deferredActionContexts(List.of(new ChunkPoint(0, 0), new ChunkPoint(1, 0)))
                        .get(new ChunkPoint(0, 0))
        );
        assertEquals(deferredAction, state.deferredActionContext(new ChunkPoint(0, 0)));
        assertNull(state.deferredActionContext(new ChunkPoint(1, 0)));

        state.finishReconciliation(drained);
        assertFalse(state.reconciliationInFlight());
        assertFalse(state.hasPendingReconciliation());
        assertTrue(state.deferredActionContexts(List.of(new ChunkPoint(0, 0))).isEmpty());
        assertTrue(state.untrackFallingEntity(entityId));
        assertFalse(state.isTrackedFallingEntity(entityId));
    }

    @Test
    void latestDeferredActionOwnsPendingChunkReconciliation() {
        CaptureSessionState state = CaptureSessionState.create(buffer());
        ChunkPoint chunk = new ChunkPoint(0, 0);
        CaptureSessionState.DeferredActionContext firstAction =
                new CaptureSessionState.DeferredActionContext("action-1", "builder", true);
        CaptureSessionState.DeferredActionContext secondAction =
                new CaptureSessionState.DeferredActionContext("action-2", "builder", true);

        assertTrue(state.markDirtyChunk(chunk, firstAction));
        assertTrue(state.markDirtyChunk(chunk, secondAction));
        state.markDirtyChunk(chunk);

        assertEquals(secondAction, state.deferredActionContexts(List.of(chunk)).get(chunk));
    }

    @Test
    void dirtyChunksWaitForSettleTicksBeforeReconciliation() {
        CaptureSessionState state = CaptureSessionState.create(buffer());
        ChunkPoint chunk = new ChunkPoint(0, 0);
        CaptureSessionState.DeferredActionContext action =
                new CaptureSessionState.DeferredActionContext("action-1", "builder", true);

        state.markDirtyChunk(chunk, action, 100L);

        assertTrue(state.drainPendingReconcileChunks(103L, 4).isEmpty());
        assertTrue(state.hasPendingReconciliation());
        assertEquals(List.of(chunk), state.drainPendingReconcileChunks(104L, 4));
        state.finishReconciliation(List.of(chunk));
        assertFalse(state.hasPendingReconciliation());
    }

    @Test
    void dirtySectionsTrackKnownSectionMutationsUntilChunkFallsBackToFullDirty() {
        CaptureSessionState state = CaptureSessionState.create(buffer());
        ChunkPoint chunk = new ChunkPoint(0, 0);
        CaptureSessionState.DeferredActionContext action =
                new CaptureSessionState.DeferredActionContext("action-1", "builder", true);

        assertTrue(state.markDirtySection(new ChunkSectionPoint(chunk, 4), action, 100L));
        assertTrue(state.markDirtySection(new ChunkSectionPoint(chunk, 5), action, 101L));

        assertEquals(Map.of(chunk, Set.of(4, 5)), state.dirtySections(List.of(chunk)));
        state.markDirtyChunk(chunk, action, 102L);
        assertTrue(state.dirtySections(List.of(chunk)).isEmpty());
        state.finishReconciliation(List.of(chunk));
        assertTrue(state.dirtyChunks().isEmpty());
    }

    private static TrackedChangeBuffer buffer() {
        return TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                Instant.parse("2026-04-20T10:15:30Z")
        );
    }

    private static StatePayload payload(String blockId) {
        net.minecraft.nbt.CompoundTag state = new net.minecraft.nbt.CompoundTag();
        state.putString("Name", blockId);
        return new StatePayload(state, null);
    }
}
