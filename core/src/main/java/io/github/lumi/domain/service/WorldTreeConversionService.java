package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.storage.repository.HistoryConversionMap;
import io.github.lumi.storage.repository.HistoryConversionMap.Kind;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.util.HashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongConsumer;

/** Converts immutable trees one leaf at a time, reusing only durably completed mappings. */
public final class WorldTreeConversionService {
    private final WorldObjectRepository source;
    private final WorldObjectRepository target;
    private final HistoryConversionMap mapping;
    private final WorldPayloadConversionService payloads;
    private final LongConsumer progress;
    private long converted;

    public WorldTreeConversionService(
            WorldObjectRepository source, WorldObjectRepository target, HistoryConversionMap mapping,
            WorldPayloadConversionService payloads, LongConsumer progress) {
        this.source = Objects.requireNonNull(source, "source");
        this.target = Objects.requireNonNull(target, "target");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
        this.payloads = Objects.requireNonNull(payloads, "payloads");
        this.progress = Objects.requireNonNull(progress, "progress");
    }

    public ObjectId dimension(ObjectId id) throws IOException {
        return convert(Kind.DIMENSION, id, () -> {
            var regions = new HashMap<>(source.readDimension(id).regions());
            for (var entry : regions.entrySet()) entry.setValue(region(entry.getValue()));
            return target.write(new DimensionTree(regions));
        });
    }

    private ObjectId region(ObjectId id) throws IOException {
        return convert(Kind.REGION, id, () -> {
            var chunks = new HashMap<>(source.readRegion(id).chunks());
            for (var entry : chunks.entrySet()) entry.setValue(chunk(entry.getValue()));
            return target.write(new RegionTree(chunks));
        });
    }

    private ObjectId chunk(ObjectId id) throws IOException {
        return convert(Kind.CHUNK, id, () -> {
            ChunkTree original = source.readChunk(id);
            var sections = new HashMap<>(original.sections());
            for (var entry : sections.entrySet()) entry.setValue(section(entry.getValue()));
            Optional<ObjectId> entities = original.entities().isPresent()
                    ? Optional.of(entities(original.entities().orElseThrow())) : Optional.empty();
            return target.write(new ChunkTree(sections, entities));
        });
    }

    public ObjectId section(ObjectId id) throws IOException {
        return convert(Kind.SECTION, id, () -> target.write(payloads.convert(source.readSection(id))));
    }

    public ObjectId entities(ObjectId id) throws IOException {
        return convert(Kind.ENTITIES, id, () -> target.write(payloads.convert(source.readEntities(id))));
    }

    private ObjectId convert(Kind kind, ObjectId id, Conversion work) throws IOException {
        var existing = mapping.read(kind, id);
        if (existing.isPresent()) return existing.orElseThrow();
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("History conversion interrupted");
        ObjectId result = work.run();
        mapping.record(kind, id, result);
        progress.accept(++converted);
        return result;
    }

    @FunctionalInterface
    private interface Conversion {
        ObjectId run() throws IOException;
    }
}
