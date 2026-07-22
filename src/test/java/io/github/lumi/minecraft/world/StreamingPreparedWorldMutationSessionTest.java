package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.SectionKey;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingPreparedWorldMutationSessionTest {
    @Test
    void boundsOneBatchToThirtyTwoChunks() {
        List<SectionKey> keys = new ArrayList<>();
        for (int chunk = 0; chunk < 40; chunk++) {
            keys.add(new SectionKey(chunk, 0, 0));
        }

        assertEquals(32, StreamingPreparedWorldMutationSession.batchEnd(keys, 0));
        assertEquals(40, StreamingPreparedWorldMutationSession.batchEnd(keys, 32));
    }

    @Test
    void boundsOneBatchToEstimatedOneHundredTwentyEightMib() {
        List<SectionKey> keys = new ArrayList<>();
        for (int section = 0; section < 1_100; section++) {
            keys.add(new SectionKey(0, section, 0));
        }

        assertEquals(1_024, StreamingPreparedWorldMutationSession.batchEnd(keys, 0));
    }

    @Test
    void boundsEntityTicketsToThirtyTwoChunks() {
        assertEquals(32, StreamingPreparedWorldMutationSession.entityBatchEnd(40, 0));
        assertEquals(40, StreamingPreparedWorldMutationSession.entityBatchEnd(40, 32));
    }
}
