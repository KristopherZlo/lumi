package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockChange;
import io.github.lumi.domain.model.MaterialDelta;
import io.github.lumi.domain.model.ObjectChange;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldDifference;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockDifferenceServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void streamsDirectionalChangesAndCountsMaterials() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        var before = objects.write(section(Map.of(
                0, "minecraft:stone",
                16, "minecraft:oak_log[axis=x]")));
        var after = objects.write(section(Map.of(
                1, "minecraft:dirt",
                16, "minecraft:oak_log[axis=y]")));
        var difference = new WorldDifference(Map.of(
                new SectionKey(-1, 2, 3), new ObjectChange(before, after)), Map.of());
        List<List<BlockChange>> batches = new ArrayList<>();

        var result = new BlockDifferenceService(objects)
                .scan(difference, 2, () -> false, batches::add);

        assertEquals(List.of(
                List.of(
                        new BlockChange(-16, 32, 48, BlockChange.Kind.REMOVED),
                        new BlockChange(-15, 32, 48, BlockChange.Kind.ADDED)),
                List.of(new BlockChange(
                        -16, 32, 49, BlockChange.Kind.CHANGED))),
                batches);
        assertEquals(3, result.changedBlocks());
        assertEquals(Map.of(
                "minecraft:stone", new MaterialDelta(1, 0),
                "minecraft:dirt", new MaterialDelta(0, 1)),
                result.materials());
        assertThrows(CancellationException.class, () ->
                new BlockDifferenceService(objects).scan(
                        difference, () -> true, ignored -> { }));
    }

    private static SectionBlob section(Map<Integer, String> replacements) {
        var blocks = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        replacements.forEach(blocks::set);
        return new SectionBlob(blocks, Map.of());
    }
}
