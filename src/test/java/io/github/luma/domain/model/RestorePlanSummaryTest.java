package io.github.luma.domain.model;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RestorePlanSummaryTest {

    @Test
    void normalizesNullTextAndCopiesTouchedChunks() {
        List<ChunkPoint> chunks = new ArrayList<>();
        chunks.add(new ChunkPoint(1, 2));

        RestorePlanSummary summary = new RestorePlanSummary(
                RestorePlanMode.PATCH_REPLAY,
                chunks,
                null,
                null,
                null
        );
        chunks.add(new ChunkPoint(3, 4));

        assertEquals("", summary.branchId());
        assertEquals("", summary.baseVersionId());
        assertEquals("", summary.targetVersionId());
        assertEquals(List.of(new ChunkPoint(1, 2)), summary.touchedChunks());
        assertThrows(UnsupportedOperationException.class, () -> summary.touchedChunks().add(new ChunkPoint(5, 6)));
    }
}
