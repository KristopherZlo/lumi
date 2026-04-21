package io.github.luma.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSessionStateTest {

    @Test
    void rootChunksDefineEnvelopeWithoutImmediateDirtyWork() {
        CaptureSessionState state = CaptureSessionState.create(buffer());

        assertTrue(state.addRootChunk(new ChunkPoint(10, -4)));
        assertFalse(state.addRootChunk(new ChunkPoint(10, -4)));
        assertEquals(List.of(new ChunkPoint(10, -4)), state.rootChunks());
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
    void reconciliationDrainAndTrackedFallingEntitiesRemainCoalesced() {
        CaptureSessionState state = CaptureSessionState.create(buffer());
        state.addRootChunk(new ChunkPoint(0, 0));
        state.markDirtyChunk(new ChunkPoint(0, 0));
        state.markDirtyChunk(new ChunkPoint(1, 0));

        UUID entityId = UUID.randomUUID();
        assertTrue(state.trackFallingEntity(entityId));
        assertFalse(state.trackFallingEntity(entityId));
        assertTrue(state.isTrackedFallingEntity(entityId));
        assertTrue(state.beginReconciliation());
        assertFalse(state.beginReconciliation());

        List<ChunkPoint> drained = state.drainPendingReconcileChunks();
        assertEquals(2, drained.size());

        state.finishReconciliation(drained);
        assertFalse(state.reconciliationInFlight());
        assertFalse(state.hasPendingReconciliation());
        assertTrue(state.untrackFallingEntity(entityId));
        assertFalse(state.isTrackedFallingEntity(entityId));
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
