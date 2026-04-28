package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChunkSectionSnapshotPayload;
import io.github.luma.domain.model.ChunkSnapshotPayload;
import io.github.luma.domain.model.StatePayload;
import io.github.luma.domain.model.StoredBlockChange;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStabilizationServiceTest {

    @Test
    void reconciliationResultExposesImmutableDeltaChanges() {
        List<StoredBlockChange> deltas = new ArrayList<>();
        deltas.add(changeAt(1));

        SessionStabilizationService.ReconciliationResult result = new SessionStabilizationService.ReconciliationResult(
                1,
                1,
                1,
                0,
                1,
                false,
                true,
                deltas
        );

        deltas.clear();

        assertEquals(1, result.deltaChanges().size());
        assertTrue(result.bufferChanged());
        assertThrows(UnsupportedOperationException.class, () -> result.deltaChanges().add(changeAt(2)));
    }

    @Test
    void emptyReconciliationResultsHaveNoDeltaChanges() {
        assertTrue(SessionStabilizationService.ReconciliationResult.noOp().deltaChanges().isEmpty());
        assertTrue(SessionStabilizationService.ReconciliationResult.busy().deltaChanges().isEmpty());
    }

    @Test
    void diffChunkKeepsBlockEntityChangesWhenSectionStorageMatches() {
        SessionStabilizationService service = new SessionStabilizationService();
        ChunkSectionSnapshotPayload section = new ChunkSectionSnapshotPayload(
                4,
                List.of(stateTag("minecraft:stone")),
                new long[0],
                0
        );
        int blockEntityIndex = io.github.luma.storage.repository.SnapshotWriter.packVerticalIndex(0, 1, 1);
        ChunkSnapshotPayload baseline = new ChunkSnapshotPayload(
                0,
                0,
                64,
                79,
                List.of(section),
                Map.of(blockEntityIndex, blockEntity("minecraft:chest", "old"))
        );
        ChunkSnapshotPayload live = new ChunkSnapshotPayload(
                0,
                0,
                64,
                79,
                List.of(section),
                Map.of(blockEntityIndex, blockEntity("minecraft:chest", "new"))
        );

        List<StoredBlockChange> changes = service.diffChunk(baseline, live, null);

        assertEquals(1, changes.size());
        assertEquals(new BlockPoint(1, 64, 1), changes.getFirst().pos());
    }

    private static StoredBlockChange changeAt(int x) {
        return new StoredBlockChange(
                new BlockPoint(x, 64, 0),
                payload("minecraft:stone"),
                payload("minecraft:dirt")
        );
    }

    private static StatePayload payload(String blockId) {
        return new StatePayload(stateTag(blockId), null);
    }

    private static CompoundTag stateTag(String blockId) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", blockId);
        return state;
    }

    private static CompoundTag blockEntity(String id, String marker) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("marker", marker);
        return tag;
    }
}
