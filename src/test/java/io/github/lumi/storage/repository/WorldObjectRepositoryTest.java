package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.ChunkTree;
import io.github.lumi.domain.model.DimensionTree;
import io.github.lumi.domain.model.SectionBlob;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
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
    }
}
