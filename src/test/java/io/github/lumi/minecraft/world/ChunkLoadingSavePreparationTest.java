package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChunkLoadingSavePreparationTest {
    @Test
    void waitsForChunkAndEntityDataWithoutRepeatingTheDurableBoundary() throws Exception {
        SectionKey key = new SectionKey(4, 5, 6);
        WorkingIndexSnapshot boundary = new WorkingIndexSnapshot(Map.of(key, 2L));
        AtomicLong clock = new AtomicLong();
        RecordingChunkAccess access = new RecordingChunkAccess();
        ChunkLoadSession chunks = new ChunkLoadSession(access, clock::get);
        SavePreparation.Session session = new ChunkLoadingSavePreparation(
                fixed(boundary), chunks).begin();

        assertFalse(session.prepareUntil(50));
        assertEquals(java.util.List.of(new ChunkCoordinate(4, 6)), access.retained);

        access.loaded.complete(null);
        assertFalse(session.prepareUntil(50));

        access.ready = true;
        assertTrue(session.prepareUntil(50));
        assertEquals(boundary, session.finish());
    }

    private static SavePreparation fixed(WorkingIndexSnapshot boundary) {
        return () -> new SavePreparation.Session() {
            private int finishes;

            @Override public boolean prepareUntil(long deadlineNanos) { return true; }
            @Override public WorkingIndexSnapshot finish() {
                if (++finishes > 1) {
                    throw new AssertionError("Durable boundary was resolved more than once");
                }
                return boundary;
            }
        };
    }

    private static final class RecordingChunkAccess implements ChunkLoadAccess {
        private final CompletableFuture<Void> loaded = new CompletableFuture<>();
        private final ArrayList<ChunkCoordinate> retained = new ArrayList<>();
        private boolean ready;

        @Override
        public CompletableFuture<Void> retain(ChunkCoordinate chunk) {
            retained.add(chunk);
            return loaded;
        }

        @Override public boolean isReady(ChunkCoordinate chunk) { return ready; }
        @Override public void release(ChunkCoordinate chunk) { }
    }
}
