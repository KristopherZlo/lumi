package io.github.lumi.storage.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

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
}
