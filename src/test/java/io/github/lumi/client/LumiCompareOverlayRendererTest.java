package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.BlockChange;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class LumiCompareOverlayRendererTest {
    @Test
    void usesDirectionalColors() {
        assertEquals(0xff55ff55,
                LumiCompareOverlayRenderer.color(BlockChange.Kind.ADDED));
        assertEquals(0xffff5555,
                LumiCompareOverlayRenderer.color(BlockChange.Kind.REMOVED));
        assertEquals(0xffffd455,
                LumiCompareOverlayRenderer.color(BlockChange.Kind.CHANGED));
    }

    @Test
    void removesSharedFacesFromAdjacentOverlayBlocks() {
        assertEquals(10, LumiCompareOverlayRenderer.exposedFaceCount(List.of(
                new BlockChange(0, 0, 0, BlockChange.Kind.ADDED),
                new BlockChange(1, 0, 0, BlockChange.Kind.ADDED))));
    }

    @Test
    void finishesAllFillsBeforeDrawingOutlines() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiCompareOverlayRenderer.java"));
        int fills = source.indexOf("draw(context, visible, xray, false)");
        int outlines = source.indexOf("draw(context, visible, xray, true)");
        assertTrue(fills >= 0 && fills < outlines);
    }
}
