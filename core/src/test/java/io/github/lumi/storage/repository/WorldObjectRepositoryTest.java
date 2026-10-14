package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldObjectRepositoryTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void persistsTypedWorldAndMerkleObjects() throws IOException {
        WorldObjectRepository repository = new WorldObjectRepository(repositoryRoot);
        SectionBlob section = new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
        var sectionId = repository.write(section);
        ChunkTree chunk = new ChunkTree(Map.of(0, sectionId), Optional.empty());
        var chunkId = repository.write(chunk);
        DimensionTree dimension = new DimensionTree(Map.of());
        var dimensionId = repository.write(dimension);

        assertEquals(section, repository.readSection(sectionId));
        assertEquals(chunk, repository.readChunk(chunkId));
        assertEquals(dimension, repository.readDimension(dimensionId));

        ChunkTree secondChunk = new ChunkTree(Map.of(1, sectionId), Optional.empty());
        var secondChunkId = repository.write(secondChunk);
        AtomicInteger submitted = new AtomicInteger();
        try (var reader = repository.beginReadSession()) {
            assertEquals(Map.of(chunkId, chunk, secondChunkId, secondChunk),
                    reader.readChunks(Set.of(chunkId, secondChunkId), task -> {
                        submitted.incrementAndGet();
                        task.run();
                    }));
        }
        assertEquals(1, submitted.get());
    }

    @Test
    void writesCapturedLeavesInOneImmutablePack() throws IOException {
        WorldObjectRepository repository = new WorldObjectRepository(repositoryRoot);
        Map<SectionKey, SectionBlob> sections = new java.util.LinkedHashMap<>();
        for (int index = 0; index < 64; index++) {
            sections.put(new SectionKey(index, 0, 0), new SectionBlob(
                    new ArrayList<>(Collections.nCopies(
                            SectionBlob.BLOCK_COUNT, "minecraft:test_" + index)),
                    Map.of()));
        }

        var written = repository.writeCaptured(sections, Map.of());

        assertEquals(sections.keySet(), written.keySet());
        for (var entry : sections.entrySet()) {
            assertEquals(entry.getValue(),
                    repository.readSection(written.get(entry.getKey())));
        }
        try (var files = Files.list(repositoryRoot.resolve("objects").resolve("packs"))) {
            assertEquals(2, files.filter(Files::isRegularFile).count());
        }
    }
}
