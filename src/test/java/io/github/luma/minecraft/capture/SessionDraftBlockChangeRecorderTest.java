package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.CaptureSessionState;
import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import io.github.luma.domain.model.TrackedChangeBuffer;
import io.github.luma.domain.model.WorldMutationSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SessionDraftBlockChangeRecorderTest {

    private static final Instant NOW = Instant.parse("2026-06-16T00:00:00Z");

    private final SessionDraftBlockChangeRecorder recorder = new SessionDraftBlockChangeRecorder();

    @Test
    void recordsBulkChangeAgainstSessionBaseline() {
        BlockPoint pos = new BlockPoint(5, 72, 9);
        TrackedChangeBuffer buffer = buffer();
        CaptureSessionState session = CaptureSessionState.create(buffer);
        session.captureBaselineChunk(new ChunkPoint(0, 0), uniformChunk(pos, "minecraft:air"));
        StoredBlockChange liveChange = new StoredBlockChange(
                pos,
                payload("minecraft:stone"),
                payload("minecraft:spruce_planks")
        );

        this.recorder.record(session, buffer, liveChange, NOW);

        assertEquals(1, buffer.orderedChanges().size());
        StoredBlockChange storedChange = buffer.orderedChanges().getFirst();
        assertEquals("minecraft:air", storedChange.oldValue().blockId());
        assertEquals("minecraft:spruce_planks", storedChange.newValue().blockId());
    }

    @Test
    void dropsNoOpAfterSessionBaselineRebase() {
        BlockPoint pos = new BlockPoint(5, 72, 9);
        TrackedChangeBuffer buffer = buffer();
        CaptureSessionState session = CaptureSessionState.create(buffer);
        session.captureBaselineChunk(new ChunkPoint(0, 0), uniformChunk(pos, "minecraft:air"));
        StoredBlockChange liveChange = new StoredBlockChange(
                pos,
                payload("minecraft:stone"),
                payload("minecraft:air")
        );

        this.recorder.record(session, buffer, liveChange, NOW);

        assertTrue(buffer.orderedChanges().isEmpty());
    }

    private static TrackedChangeBuffer buffer() {
        return TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.AXIOM,
                NOW
        );
    }

    private static ChunkSnapshotPayload uniformChunk(BlockPoint pos, String blockId) {
        return new ChunkSnapshotPayload(
                pos.x() >> 4,
                pos.z() >> 4,
                (pos.y() >> 4) << 4,
                ((pos.y() >> 4) << 4) + 15,
                List.of(new ChunkSectionSnapshotPayload(pos.y() >> 4, List.of(stateTag(blockId)), new long[0], 0)),
                Map.of()
        );
    }

    private static StatePayload payload(String blockId) {
        return new StatePayload(stateTag(blockId), null);
    }

    private static CompoundTag stateTag(String blockId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", blockId);
        return tag;
    }
}
