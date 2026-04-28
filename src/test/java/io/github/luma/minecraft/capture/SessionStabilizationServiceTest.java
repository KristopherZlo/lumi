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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

    @Test
    void diffChunkReadsMinecraftPaddedPaletteStorage() {
        SessionStabilizationService service = new SessionStabilizationService();
        short[] logicalIndexes = patternedIndexes();
        List<CompoundTag> baselinePalette = numberedPalette();
        List<CompoundTag> livePalette = new ArrayList<>();
        int[] liveIndexByLogicalState = new int[32];
        for (int liveIndex = 0; liveIndex < 32; liveIndex++) {
            int logicalState = (liveIndex + 5) & 31;
            livePalette.add(baselinePalette.get(logicalState));
            liveIndexByLogicalState[logicalState] = liveIndex;
        }

        short[] liveIndexes = new short[logicalIndexes.length];
        for (int index = 0; index < logicalIndexes.length; index++) {
            liveIndexes[index] = (short) liveIndexByLogicalState[logicalIndexes[index]];
        }
        ChunkSectionSnapshotPayload baselineSection = new ChunkSectionSnapshotPayload(
                0,
                baselinePalette,
                packMinecraftIndexes(logicalIndexes, 5),
                5
        );
        ChunkSectionSnapshotPayload liveSection = new ChunkSectionSnapshotPayload(
                0,
                livePalette,
                packMinecraftIndexes(liveIndexes, 5),
                5
        );
        ChunkSnapshotPayload baseline = new ChunkSnapshotPayload(
                0,
                0,
                0,
                15,
                List.of(baselineSection),
                Map.of()
        );
        ChunkSnapshotPayload live = new ChunkSnapshotPayload(
                0,
                0,
                0,
                15,
                List.of(liveSection),
                Map.of()
        );

        assertArrayEquals(logicalIndexes, baselineSection.unpackPaletteIndexes());
        assertTrue(service.diffChunk(baseline, live, null).isEmpty());
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

    private static List<CompoundTag> numberedPalette() {
        List<CompoundTag> palette = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            palette.add(stateTag("minecraft:lumi_state_" + index));
        }
        return palette;
    }

    private static short[] patternedIndexes() {
        short[] indexes = new short[4096];
        for (int index = 0; index < indexes.length; index++) {
            indexes[index] = (short) ((index * 7 + 3) & 31);
        }
        return indexes;
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
}
