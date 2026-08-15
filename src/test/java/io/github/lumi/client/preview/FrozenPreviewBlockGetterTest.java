package io.github.lumi.client.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.SectionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FrozenPreviewBlockGetterTest {
    @Test
    void usesFloorCoordinatesAndVanillaSectionIndexOrder() {
        BlockPos negative = new BlockPos(-1, -17, -16);
        assertEquals(new SectionKey(-1, -2, -1),
                FrozenPreviewBlockGetter.sectionKey(negative));
        assertEquals(15 | 0 << 4 | 15 << 8,
                FrozenPreviewBlockGetter.index(negative));
    }

    @Test
    void snapshotReaderUsesPublishedCommitAndBoundsItsInput() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/CommitPreviewSnapshotReader.java"));

        assertTrue(source.contains("commits.read(commitId).tree()"));
        assertTrue(source.contains("bounds.intersects(key)"));
        assertTrue(source.contains("MAX_SECTIONS = 256"));
        assertTrue(source.contains("MinecraftBlockStateDecoder"));
    }

    @Test
    void immutablePreviewUsesBiomeTintAndNeutralLighting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/preview/FrozenPreviewBlockGetter.java"));

        assertTrue(source.contains("PREVIEW_LIGHT = 15"));
        assertTrue(source.contains(
                "return lighting.getBlockTint(position, resolver);"));
        assertTrue(source.contains("return PREVIEW_LIGHT;"));
        assertTrue(source.contains("return true;"));
    }
}
