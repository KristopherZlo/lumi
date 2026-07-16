package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.ChunkInRegion;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.RegionCoordinate;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MerkleTreeEditorTest {
    @TempDir
    Path repositoryRoot;

    @Test
    void oneSectionWritesOnlyPayloadAndMerklePath() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        ObjectId section = objects.write(airSection());
        MerkleTreeEditor editor = new MerkleTreeEditor(objects);

        ObjectId root = editor.update(Optional.empty(), Map.of(new SectionKey(-33, 5, 64), section));

        var dimension = objects.readDimension(root);
        ObjectId regionId = dimension.regions().get(new RegionCoordinate(-2, 2));
        var region = objects.readRegion(regionId);
        ObjectId chunkId = region.chunks().get(new ChunkInRegion(31, 0));
        assertEquals(section, objects.readChunk(chunkId).sections().get(5));
        try (var files = Files.walk(repositoryRoot.resolve("objects"))) {
            var names = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
            assertEquals(1, names.stream().filter(name -> name.endsWith(".lz4")).count());
            assertEquals(1, names.stream().filter(name -> name.endsWith(".pack")).count());
            assertEquals(1, names.stream().filter(name -> name.endsWith(".idx")).count());
        }
    }

    @Test
    void identicalUpdateReusesEveryObject() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        ObjectId section = objects.write(airSection());
        MerkleTreeEditor editor = new MerkleTreeEditor(objects);
        Map<io.github.lumi.domain.model.HistoryKey, ObjectId> change =
                Map.of(new SectionKey(0, 0, 0), section);
        ObjectId first = editor.update(Optional.empty(), change);

        ObjectId second = editor.update(Optional.of(first), change);

        assertEquals(first, second);
    }

    private static SectionBlob airSection() {
        return new SectionBlob(
                new ArrayList<>(Collections.nCopies(SectionBlob.BLOCK_COUNT, "minecraft:air")), Map.of());
    }
}
