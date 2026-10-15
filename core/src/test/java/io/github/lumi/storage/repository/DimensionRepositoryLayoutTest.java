package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.HistoryGeneration;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.domain.model.WorldDataVersion;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionRepositoryLayoutTest {
    @Test
    void mapsEveryDimensionIdToOneSafeDirectoryBelowHistoryRoot() {
        Path world = Path.of("world").toAbsolutePath();
        DimensionRepositoryLayout layout = new DimensionRepositoryLayout(world);

        Path overworld = layout.resolve("minecraft:overworld");
        Path custom = layout.resolve("builder:zones/redstone");
        Path hostile = layout.resolve("builder:../../outside");

        assertTrue(overworld.startsWith(world.resolve("lumi/history")));
        assertEquals(1, world.resolve("lumi/history").relativize(overworld).getNameCount());
        assertEquals(1, world.resolve("lumi/history").relativize(hostile).getNameCount());
        assertNotEquals(overworld, custom);
        assertNotEquals(custom, hostile);
        assertEquals("builder:zones/redstone", layout.dimensionId(custom));
    }

    @Test
    void rejectsBlankOrOversizedIdsAndForeignPaths() {
        DimensionRepositoryLayout layout = new DimensionRepositoryLayout(Path.of("world"));

        assertThrows(IllegalArgumentException.class, () -> layout.resolve(" "));
        assertThrows(IllegalArgumentException.class, () -> layout.resolve("x".repeat(1025)));
        assertThrows(IllegalArgumentException.class, () -> layout.dimensionId(Path.of("elsewhere")));
    }

    @Test
    void routesPreviewReadsAndWritesToThePublishedGeneration(@TempDir Path world) throws Exception {
        var layout = new DimensionRepositoryLayout(world);
        String dimension = "minecraft:overworld";
        Path original = layout.resolve(dimension);
        assertEquals(original, layout.resolveCurrent(dimension, WorldDataVersion.LEGACY_12111));
        var generations = new HistoryGenerationRepository(original);
        var version = new WorldDataVersion(4786);
        var prepared = generations.prepare(HistoryGeneration.LEGACY, version);
        var commit = new CommitId(ObjectId.hash(new byte[] {1}));
        new VersionPreviewRepository(generations.directory(prepared)).save(commit, new byte[] {1, 2, 3});
        generations.markVerified(HistoryGeneration.LEGACY, prepared);
        generations.publish(HistoryGeneration.LEGACY, prepared);
        var previews = new VersionPreviewRepository(layout.resolveCurrent(dimension, version));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {1, 2, 3}, previews.load(commit).orElseThrow());
        previews.save(commit, new byte[] {4});
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {4},
                new VersionPreviewRepository(generations.directory(prepared)).load(commit).orElseThrow());
        assertTrue(new VersionPreviewRepository(original).load(commit).isEmpty());
        assertThrows(java.io.IOException.class, () -> layout.resolveCurrent(dimension, WorldDataVersion.LEGACY_12111));
    }
}
