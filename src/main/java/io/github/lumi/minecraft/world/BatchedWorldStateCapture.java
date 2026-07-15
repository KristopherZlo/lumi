package io.github.lumi.minecraft.world;

import io.github.lumi.domain.model.CommitStatistics;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorkingIndexSnapshot;
import io.github.lumi.domain.service.CapturedWorldState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Copies dirty decoded payloads without exceeding the coordinator deadline. */
public final class BatchedWorldStateCapture implements WorldStateCapture {
    private final WorldStateReader reader;
    private final LongSupplier nanoTime;

    public BatchedWorldStateCapture(WorldStateReader reader) {
        this(reader, System::nanoTime);
    }

    BatchedWorldStateCapture(WorldStateReader reader, LongSupplier nanoTime) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public CaptureSession begin(WorkingIndexSnapshot dirty) {
        return new Session(Objects.requireNonNull(dirty, "dirty"));
    }

    private final class Session implements CaptureSession {
        private final WorkingIndexSnapshot dirty;
        private final List<HistoryKey> keys;
        private final Map<SectionKey, SectionBlob> sections = new HashMap<>();
        private final Map<EntityChunkKey, EntityChunkBlob> entities = new HashMap<>();
        private int next;

        private Session(WorkingIndexSnapshot dirty) {
            this.dirty = dirty;
            keys = new ArrayList<>(dirty.generations().keySet());
        }

        @Override
        public boolean captureUntil(long deadlineNanos) throws IOException {
            while (next < keys.size() && nanoTime.getAsLong() < deadlineNanos) {
                HistoryKey key = keys.get(next++);
                if (key instanceof SectionKey section) {
                    sections.put(section, reader.read(section));
                } else {
                    EntityChunkKey entityChunk = (EntityChunkKey) key;
                    entities.put(entityChunk, reader.read(entityChunk));
                }
            }
            return next == keys.size();
        }

        @Override
        public CapturedWorldState finish() {
            if (next != keys.size()) {
                throw new IllegalStateException("World capture is not complete");
            }
            int entityCount = entities.values().stream()
                    .mapToInt(chunk -> chunk.entities().size()).sum();
            return new CapturedWorldState(
                    sections, entities, dirty,
                    new CommitStatistics(
                            sections.size(), entities.size(),
                            Math.multiplyExact((long) sections.size(), SectionBlob.BLOCK_COUNT),
                            entityCount));
        }
    }
}
