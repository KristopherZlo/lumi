package io.github.lumi.domain.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.MaterialDelta;
import io.github.lumi.domain.model.ObjectChange;
import io.github.lumi.domain.model.SectionBlob;
import io.github.lumi.domain.model.SectionKey;
import io.github.lumi.domain.model.WorldDifference;
import io.github.lumi.storage.repository.WorldObjectRepository;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaterialCountServiceTest {
    @TempDir Path repositoryRoot;

    @Test
    void countsNetBlockMaterialsOnlyInChangedSections() throws Exception {
        WorldObjectRepository objects = new WorldObjectRepository(repositoryRoot);
        var before = objects.write(section(
                "minecraft:stone", "minecraft:oak_log[axis=x]"));
        var after = objects.write(section(
                "minecraft:dirt", "minecraft:oak_log[axis=y]", "minecraft:stone"));
        var difference = new WorldDifference(
                Map.of(new SectionKey(0, 0, 0), new ObjectChange(before, after)), Map.of());

        var materials = new MaterialCountService(objects).count(difference);

        assertEquals(Map.of("minecraft:dirt", new MaterialDelta(0, 1)), materials);
        assertThrows(CancellationException.class,
                () -> new MaterialCountService(objects).count(difference, () -> true));
    }

    private static SectionBlob section(String... states) {
        var blocks = new ArrayList<>(Collections.nCopies(
                SectionBlob.BLOCK_COUNT, "minecraft:air"));
        for (int index = 0; index < states.length; index++) {
            blocks.set(index, states[index]);
        }
        return new SectionBlob(blocks, Map.of());
    }
}
