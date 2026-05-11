package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayVolumeMergerTest {

    private static final int SECTION_SIZE = 16;
    private final OverlayVolumeMerger merger = new OverlayVolumeMerger();

    @Test
    void longRunsAreSplitIntoShortSectionTiles() {
        List<OverlayVolumeMerger.OverlayBox> boxes = this.merger.merge(sectionLine(3126));

        assertFalse(boxes.isEmpty());
        assertTrue(boxes.size() <= OverlayVolumeMerger.MAX_MERGED_BOXES);
        assertTrue(boxes.stream().allMatch(box -> sectionSpanX(box) <= 2));
    }

    @Test
    void denseCuboidsAreSplitIntoShortSectionTiles() {
        List<OverlayVolumeMerger.OverlayBox> boxes = this.merger.merge(sectionCuboid(5, 4, 4));

        assertFalse(boxes.isEmpty());
        assertTrue(boxes.size() <= OverlayVolumeMerger.MAX_MERGED_BOXES);
        assertTrue(boxes.stream().allMatch(OverlayVolumeMergerTest::hasShortSectionSpans));
    }

    private static List<BlockPoint> sectionLine(int sectionCount) {
        List<BlockPoint> points = new ArrayList<>(sectionCount);
        for (int sectionX = 0; sectionX < sectionCount; sectionX++) {
            points.add(new BlockPoint(sectionX * SECTION_SIZE, 64, 0));
        }
        return List.copyOf(points);
    }

    private static List<BlockPoint> sectionCuboid(int spanX, int spanY, int spanZ) {
        List<BlockPoint> points = new ArrayList<>(spanX * spanY * spanZ);
        for (int sectionY = 0; sectionY < spanY; sectionY++) {
            for (int sectionZ = 0; sectionZ < spanZ; sectionZ++) {
                for (int sectionX = 0; sectionX < spanX; sectionX++) {
                    points.add(new BlockPoint(
                            sectionX * SECTION_SIZE,
                            (4 + sectionY) * SECTION_SIZE,
                            sectionZ * SECTION_SIZE
                    ));
                }
            }
        }
        return List.copyOf(points);
    }

    private static boolean hasShortSectionSpans(OverlayVolumeMerger.OverlayBox box) {
        return sectionSpanX(box) <= 2
                && sectionSpanY(box) <= 2
                && sectionSpanZ(box) <= 2;
    }

    private static int sectionSpanX(OverlayVolumeMerger.OverlayBox box) {
        return sectionSpan(box.minX(), box.maxX());
    }

    private static int sectionSpanY(OverlayVolumeMerger.OverlayBox box) {
        return sectionSpan(box.minY(), box.maxY());
    }

    private static int sectionSpanZ(OverlayVolumeMerger.OverlayBox box) {
        return sectionSpan(box.minZ(), box.maxZ());
    }

    private static int sectionSpan(int min, int max) {
        return Math.floorDiv(max - 1, SECTION_SIZE) - Math.floorDiv(min, SECTION_SIZE) + 1;
    }
}
