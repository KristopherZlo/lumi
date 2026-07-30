package io.github.lumi.storage.repository;

import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.storage.object.EntityChunkBlobCodec;
import io.github.lumi.storage.object.MerkleNodeCodec;
import io.github.lumi.storage.object.ObjectStore;
import io.github.lumi.storage.object.SectionBlobCodec;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongConsumer;

public final class WorldObjectRepository {
    private final ObjectStore store;
    private final SectionBlobCodec sectionCodec = new SectionBlobCodec();
    private final EntityChunkBlobCodec entityCodec = new EntityChunkBlobCodec();
    private final MerkleNodeCodec merkleCodec = new MerkleNodeCodec();

    public WorldObjectRepository(Path dimensionRepository) {
        Objects.requireNonNull(dimensionRepository, "dimensionRepository");
        store = new ObjectStore(dimensionRepository.resolve("objects"));
    }

    public ObjectId write(SectionBlob section) throws IOException {
        return store.write(sectionCodec.encode(section));
    }

    public ObjectId write(EntityChunkBlob entities) throws IOException {
        return store.write(entityCodec.encode(entities));
    }

    public Map<HistoryKey, ObjectId> writeCaptured(
            Map<SectionKey, SectionBlob> sections,
            Map<EntityChunkKey, EntityChunkBlob> entities) throws IOException {
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(entities, "entities");
        try (WriteBatch batch = beginBatch()) {
            Map<HistoryKey, ObjectId> written = batch.writeCaptured(sections, entities);
            batch.publish();
            return written;
        }
    }

    public ObjectId write(ChunkTree chunk) throws IOException {
        return store.write(merkleCodec.encode(chunk));
    }

    public ObjectId write(RegionTree region) throws IOException {
        return store.write(merkleCodec.encode(region));
    }

    public ObjectId write(DimensionTree dimension) throws IOException {
        return store.write(merkleCodec.encode(dimension));
    }

    public SectionBlob readSection(ObjectId id) throws IOException {
        return sectionCodec.decode(store.read(id));
    }

    public EntityChunkBlob readEntities(ObjectId id) throws IOException {
        return entityCodec.decode(store.read(id));
    }

    public ChunkTree readChunk(ObjectId id) throws IOException {
        return merkleCodec.decodeChunk(store.read(id));
    }

    public RegionTree readRegion(ObjectId id) throws IOException {
        return merkleCodec.decodeRegion(store.read(id));
    }

    public DimensionTree readDimension(ObjectId id) throws IOException {
        return merkleCodec.decodeDimension(store.read(id));
    }

    public byte[] readCanonical(ObjectId id) throws IOException {
        return store.read(Objects.requireNonNull(id, "id"));
    }

    public ObjectId writeCanonical(ObjectId expected, byte[] payload)
            throws IOException {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(payload, "payload");
        ObjectId actual = ObjectId.hash(payload);
        if (!actual.equals(expected)) {
            throw new IOException("Object payload hash does not match package manifest");
        }
        return store.write(payload);
    }

    public WriteBatch beginBatch() throws IOException {
        return new WriteBatch(store.beginBatch());
    }

    public ReadSession beginReadSession() {
        return new ReadSession(store.beginReadSession());
    }

    public List<ObjectId> physicalReadOrder(Set<ObjectId> ids) throws IOException {
        return store.physicalReadOrder(ids);
    }

    public final class WriteBatch implements AutoCloseable {
        private final ObjectStore.WriteBatch batch;

        private WriteBatch(ObjectStore.WriteBatch batch) {
            this.batch = Objects.requireNonNull(batch, "batch");
        }

        public Map<HistoryKey, ObjectId> writeCaptured(
                Map<SectionKey, SectionBlob> sections,
                Map<EntityChunkKey, EntityChunkBlob> entities) throws IOException {
            return writeCaptured(sections, entities, ignored -> { });
        }

        public Map<HistoryKey, ObjectId> writeCaptured(
                Map<SectionKey, SectionBlob> sections,
                Map<EntityChunkKey, EntityChunkBlob> entities,
                LongConsumer progress) throws IOException {
            Objects.requireNonNull(sections, "sections");
            Objects.requireNonNull(entities, "entities");
            Objects.requireNonNull(progress, "progress");
            Map<HistoryKey, ObjectId> written = new LinkedHashMap<>();
            for (var section : sections.entrySet()) {
                written.put(section.getKey(), write(section.getValue()));
                progress.accept(written.size());
            }
            for (var entityChunk : entities.entrySet()) {
                written.put(entityChunk.getKey(), write(entityChunk.getValue()));
                progress.accept(written.size());
            }
            return Map.copyOf(written);
        }

        public ObjectId write(SectionBlob section) throws IOException {
            return batch.write(sectionCodec.encode(section));
        }

        public ObjectId write(EntityChunkBlob entities) throws IOException {
            return batch.write(entityCodec.encode(entities));
        }

        public ObjectId write(ChunkTree chunk) throws IOException {
            return batch.write(merkleCodec.encode(chunk));
        }

        public ObjectId write(RegionTree region) throws IOException {
            return batch.write(merkleCodec.encode(region));
        }

        public ObjectId write(DimensionTree dimension) throws IOException {
            return batch.write(merkleCodec.encode(dimension));
        }

        public void publish() throws IOException {
            batch.publish();
        }

        @Override
        public void close() throws IOException {
            batch.close();
        }
    }

    public final class ReadSession implements Closeable {
        private final ObjectStore.ReadSession objects;

        private ReadSession(ObjectStore.ReadSession objects) {
            this.objects = objects;
        }

        public SectionBlob readSection(ObjectId id) throws IOException {
            return sectionCodec.decode(objects.read(id));
        }

        public EntityChunkBlob readEntities(ObjectId id) throws IOException {
            return entityCodec.decode(objects.read(id));
        }

        public ChunkTree readChunk(ObjectId id) throws IOException {
            return merkleCodec.decodeChunk(objects.read(id));
        }

        /**
         * Reads physically ordered chunk trees with one independent helper
         * session. The number of tasks stays constant for every Restore size.
         */
        public Map<ObjectId, ChunkTree> readChunks(
                Set<ObjectId> ids, Executor background) throws IOException {
            Objects.requireNonNull(ids, "ids");
            Objects.requireNonNull(background, "background");
            List<ObjectId> ordered = physicalReadOrder(ids);
            if (ordered.size() < 2) {
                return readChunkRange(ordered, 0, ordered.size());
            }
            int split = ordered.size() / 2;
            CompletableFuture<Map<ObjectId, ChunkTree>> second;
            try {
                second = CompletableFuture.supplyAsync(
                        () -> readChunkRangeUnchecked(ordered, split, ordered.size()),
                        background);
            } catch (RejectedExecutionException rejected) {
                return readChunkRange(ordered, 0, ordered.size());
            }
            Map<ObjectId, ChunkTree> combined;
            try {
                combined = new HashMap<>(readChunkRange(ordered, 0, split));
            } catch (IOException failed) {
                second.cancel(true);
                throw failed;
            }
            try {
                combined.putAll(second.join());
            } catch (CompletionException failed) {
                if (failed.getCause() instanceof UncheckedIOException io) {
                    throw io.getCause();
                }
                throw failed;
            }
            return Map.copyOf(combined);
        }

        public RegionTree readRegion(ObjectId id) throws IOException {
            return merkleCodec.decodeRegion(objects.read(id));
        }

        public DimensionTree readDimension(ObjectId id) throws IOException {
            return merkleCodec.decodeDimension(objects.read(id));
        }

        private Map<ObjectId, ChunkTree> readChunkRange(
                List<ObjectId> ids, int start, int end) throws IOException {
            Map<ObjectId, ChunkTree> chunks = new HashMap<>();
            for (int index = start; index < end; index++) {
                ObjectId id = ids.get(index);
                chunks.put(id, readChunk(id));
            }
            return chunks;
        }

        private Map<ObjectId, ChunkTree> readChunkRangeUnchecked(
                List<ObjectId> ids, int start, int end) {
            try (ReadSession reader = beginReadSession()) {
                return reader.readChunkRange(ids, start, end);
            } catch (IOException failed) {
                throw new UncheckedIOException(failed);
            }
        }

        @Override
        public void close() throws IOException {
            objects.close();
        }
    }
}
