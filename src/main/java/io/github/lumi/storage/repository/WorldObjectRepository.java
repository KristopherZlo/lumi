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
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

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
        Map<HistoryKey, ObjectId> written = new LinkedHashMap<>();
        try (ObjectStore.WriteBatch batch = store.beginBatch()) {
            for (var section : sections.entrySet()) {
                written.put(section.getKey(),
                        batch.write(sectionCodec.encode(section.getValue())));
            }
            for (var entityChunk : entities.entrySet()) {
                written.put(entityChunk.getKey(),
                        batch.write(entityCodec.encode(entityChunk.getValue())));
            }
            batch.publish();
        }
        return Map.copyOf(written);
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
}
