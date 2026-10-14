package io.github.lumi.domain.service;

import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.Commit;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionTree;
import io.github.lumi.storage.object.CommitCodec;
import io.github.lumi.storage.object.EntityChunkBlobCodec;
import io.github.lumi.storage.object.MerkleNodeCodec;
import io.github.lumi.storage.object.SectionBlobCodec;
import io.github.lumi.storage.repository.CommitRepository;
import io.github.lumi.storage.repository.HistoryConversionMap;
import io.github.lumi.storage.repository.HistoryConversionMap.Kind;
import io.github.lumi.storage.repository.VersionDisplayNameRepository;
import io.github.lumi.storage.repository.VersionPreviewRepository;
import io.github.lumi.storage.repository.VersionTagRepository;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Optional;
import java.util.function.LongConsumer;

/** Recomputes target identities with the current adapter before a generation is exposed. */
public final class HistoryConversionVerifier {
    private final Path sourceDirectory;
    private final Path targetDirectory;
    private final WorldObjectRepository source;
    private final WorldObjectRepository target;
    private final HistoryConversionMap mapping;
    private final WorldPayloadConversionService payloads;
    private final MerkleNodeCodec nodes = new MerkleNodeCodec();
    private long verified;

    public HistoryConversionVerifier(Path sourceDirectory, Path targetDirectory,
            HistoryConversionMap mapping, WorldPayloadConversionService payloads) {
        this.sourceDirectory = sourceDirectory;
        this.targetDirectory = targetDirectory;
        source = new WorldObjectRepository(sourceDirectory);
        target = new WorldObjectRepository(targetDirectory);
        this.mapping = mapping;
        this.payloads = payloads;
    }

    public void verify(LongConsumer progress) throws IOException {
        verified = 0;
        mapping.visit((kind, original, converted) -> {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("History verification interrupted");
            byte[] expected = expected(kind, original);
            byte[] actual = kind == Kind.COMMIT
                    ? new CommitRepository(targetDirectory).readCanonical(new CommitId(converted))
                    : target.readCanonical(converted);
            if (!ObjectId.hash(expected).equals(converted) || !Arrays.equals(expected, actual)) {
                throw new IOException("Converted history object differs from the current adapter: " + original);
            }
            if (kind == Kind.COMMIT) verifySidecars(new CommitId(original), new CommitId(converted));
            progress.accept(++verified);
        });
    }

    private byte[] expected(Kind kind, ObjectId id) throws IOException {
        return switch (kind) {
            case SECTION -> new SectionBlobCodec().encode(payloads.convert(source.readSection(id)));
            case ENTITIES -> new EntityChunkBlobCodec().encode(payloads.convert(source.readEntities(id)));
            case CHUNK -> {
                var original = source.readChunk(id);
                var sections = new HashMap<>(original.sections());
                for (var entry : sections.entrySet()) entry.setValue(mapped(Kind.SECTION, entry.getValue()));
                var entities = original.entities().isPresent()
                        ? Optional.of(mapped(Kind.ENTITIES, original.entities().orElseThrow())) : Optional.<ObjectId>empty();
                yield nodes.encode(new ChunkTree(sections, entities));
            }
            case REGION -> {
                var chunks = new HashMap<>(source.readRegion(id).chunks());
                for (var entry : chunks.entrySet()) entry.setValue(mapped(Kind.CHUNK, entry.getValue()));
                yield nodes.encode(new RegionTree(chunks));
            }
            case DIMENSION -> {
                var regions = new HashMap<>(source.readDimension(id).regions());
                for (var entry : regions.entrySet()) entry.setValue(mapped(Kind.REGION, entry.getValue()));
                yield nodes.encode(new DimensionTree(regions));
            }
            case COMMIT -> {
                var commit = new CommitRepository(sourceDirectory).read(new CommitId(id));
                var parents = new ArrayList<CommitId>(2);
                for (var parent : commit.parents()) parents.add(new CommitId(mapped(Kind.COMMIT, parent.value())));
                yield new CommitCodec().encode(new Commit(mapped(Kind.DIMENSION, commit.tree()), parents,
                        commit.author(), commit.message(), commit.timestamp(), commit.workspaceId(), commit.zoneId(),
                        commit.kind(), commit.statistics(), commit.playerSpawns(),
                        Optional.of(commit.conversionOrigin().orElse(new CommitId(id)))));
            }
        };
    }

    private ObjectId mapped(Kind kind, ObjectId source) throws IOException {
        return mapping.read(kind, source).orElseThrow(() -> new IOException("Missing conversion dependency: " + source));
    }

    private void verifySidecars(CommitId original, CommitId converted) throws IOException {
        if (!new VersionDisplayNameRepository(sourceDirectory).read(original)
                .equals(new VersionDisplayNameRepository(targetDirectory).read(converted))
                || !new VersionTagRepository(sourceDirectory).read(original)
                .equals(new VersionTagRepository(targetDirectory).read(converted))
                || !Arrays.equals(new VersionPreviewRepository(sourceDirectory).load(original).orElse(null),
                new VersionPreviewRepository(targetDirectory).load(converted).orElse(null))) {
            throw new IOException("Converted commit metadata differs from source: " + original);
        }
    }
}
