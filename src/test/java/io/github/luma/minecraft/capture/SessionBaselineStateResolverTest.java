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

final class SessionBaselineStateResolverTest {

    private final SessionBaselineStateResolver resolver = new SessionBaselineStateResolver();

    @Test
    void rebasesSameSessionTransientRemovalToCapturedBaseline() {
        BlockPoint pos = new BlockPoint(3, 309, -9);
        CaptureSessionState session = sessionWithBaseline(uniformChunk(pos, "minecraft:air"));
        StoredBlockChange eventChange = new StoredBlockChange(
                pos,
                payload("minecraft:tnt"),
                payload("minecraft:air")
        );

        StoredBlockChange rebased = this.resolver.rebaseToSessionBaseline(session, eventChange);

        assertEquals("minecraft:air", rebased.oldValue().blockId());
        assertEquals("minecraft:air", rebased.newValue().blockId());
        assertTrue(rebased.isNoOp());
    }

    @Test
    void keepsSavedTntRemovalWhenBaselineContainsTnt() {
        BlockPoint pos = new BlockPoint(3, 309, -9);
        CaptureSessionState session = sessionWithBaseline(chunkWithSingleState(
                pos,
                stateTag("minecraft:air"),
                stateTag("minecraft:tnt")
        ));
        StoredBlockChange eventChange = new StoredBlockChange(
                pos,
                payload("minecraft:tnt"),
                payload("minecraft:air")
        );

        StoredBlockChange rebased = this.resolver.rebaseToSessionBaseline(session, eventChange);

        assertEquals("minecraft:tnt", rebased.oldValue().blockId());
        assertEquals("minecraft:air", rebased.newValue().blockId());
    }

    private static CaptureSessionState sessionWithBaseline(ChunkSnapshotPayload baseline) {
        TrackedChangeBuffer buffer = TrackedChangeBuffer.create(
                "session",
                "project",
                "main",
                "v0001",
                "tester",
                WorldMutationSource.PLAYER,
                Instant.parse("2026-05-31T10:00:00Z")
        );
        CaptureSessionState session = CaptureSessionState.create(buffer);
        session.captureBaselineChunk(new ChunkPoint(baseline.chunkX(), baseline.chunkZ()), baseline);
        return session;
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

    private static ChunkSnapshotPayload chunkWithSingleState(
            BlockPoint pos,
            CompoundTag defaultState,
            CompoundTag specialState
    ) {
        short[] indexes = new short[4096];
        indexes[ChunkSectionSnapshotPayload.localIndex(pos.x() & 15, pos.y() & 15, pos.z() & 15)] = 1;
        int bitsPerEntry = 1;
        return new ChunkSnapshotPayload(
                pos.x() >> 4,
                pos.z() >> 4,
                (pos.y() >> 4) << 4,
                ((pos.y() >> 4) << 4) + 15,
                List.of(new ChunkSectionSnapshotPayload(
                        pos.y() >> 4,
                        List.of(defaultState, specialState),
                        packMinecraftIndexes(indexes, bitsPerEntry),
                        bitsPerEntry
                )),
                Map.of()
        );
    }

    private static long[] packMinecraftIndexes(short[] indexes, int bitsPerEntry) {
        int valuesPerLong = Long.SIZE / bitsPerEntry;
        long[] packed = new long[(indexes.length + valuesPerLong - 1) / valuesPerLong];
        long mask = (1L << bitsPerEntry) - 1L;
        for (int index = 0; index < indexes.length; index++) {
            long value = indexes[index] & mask;
            int storageIndex = index / valuesPerLong;
            int bitOffset = (index - storageIndex * valuesPerLong) * bitsPerEntry;
            packed[storageIndex] |= value << bitOffset;
        }
        return packed;
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
