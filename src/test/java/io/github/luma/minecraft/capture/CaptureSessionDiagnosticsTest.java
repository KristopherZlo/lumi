package io.github.luma.minecraft.capture;

import io.github.luma.domain.model.ChunkPoint;
import io.github.luma.domain.model.WorldMutationSource;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureSessionDiagnosticsTest {

    @Test
    void recordsLastMutationAndActiveRegion() {
        CaptureSessionDiagnostics diagnostics = new CaptureSessionDiagnostics();

        diagnostics.record(
                WorldMutationSource.PLAYER,
                new BlockPos(16, 64, 0),
                null,
                null,
                false,
                true
        );

        assertEquals(1, diagnostics.acceptedMutations());
        assertEquals("PLAYER", diagnostics.lastSource());
        assertEquals(new ChunkPoint(1, 0), diagnostics.lastChunk());
        assertEquals("minecraft:air", diagnostics.lastOldBlockId());
        assertEquals("minecraft:air", diagnostics.lastNewBlockId());
        assertFalse(diagnostics.lastOldBlockEntity());
        assertTrue(diagnostics.lastNewBlockEntity());
        assertTrue(diagnostics.isWithinActiveRegion(new ChunkPoint(2, 1), 1));
        assertFalse(diagnostics.isWithinActiveRegion(new ChunkPoint(3, 1), 1));
    }

    @Test
    void summarizesTopSourcesAndTransitions() {
        CaptureSessionDiagnostics diagnostics = new CaptureSessionDiagnostics();

        diagnostics.record(WorldMutationSource.FLUID, BlockPos.ZERO, null, null, false, false);
        diagnostics.record(WorldMutationSource.PLAYER, BlockPos.ZERO, null, null, false, false);
        diagnostics.record(WorldMutationSource.PLAYER, BlockPos.ZERO, null, null, false, false);

        assertEquals("PLAYER=2, FLUID=1", diagnostics.describeTopSources(2));
        assertEquals("minecraft:air -> minecraft:air=3", diagnostics.describeTopTransitions(1));
    }
}
