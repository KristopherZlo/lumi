package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.EntityChunkBlob;
import io.github.lumi.domain.model.EntityChunkKey;
import io.github.lumi.domain.model.HistoryKey;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldObjectGraphTest {
    @TempDir Path repositoryRoot;

    @Test
    void scansEveryMerkleObjectAndAbsoluteLeafKey() throws IOException {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        ObjectId section = objects.write(new SectionBlob(
                new ArrayList<>(Collections.nCopies(
                        SectionBlob.BLOCK_COUNT, "minecraft:air")),
                Map.of()));
        ObjectId entities = objects.write(new EntityChunkBlob(java.util.List.of()));
        Map<HistoryKey, ObjectId> leaves = Map.of(
                new SectionKey(-33, 5, 64), section,
                new EntityChunkKey(-33, 64), entities);
        ObjectId root = new MerkleTreeEditor(objects).update(Optional.empty(), leaves);

        WorldObjectGraph.Snapshot snapshot = new WorldObjectGraph(objects).scan(root);

        assertEquals(leaves, snapshot.leaves());
        assertEquals(5, snapshot.reachable().size());
        assertEquals(Set.of(section, entities), snapshot.reachable().stream()
                .filter(id -> id.equals(section) || id.equals(entities))
                .collect(java.util.stream.Collectors.toSet()));
    }
}
