package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompareOverlayRendererStateTest {

    @AfterEach
    void tearDown() {
        CompareOverlayRenderer.clear();
        CompareOverlayRenderer.setXrayEnabled(false);
    }

    @Test
    void xrayHoldStateCanBeUpdatedWithoutHidingOverlay() {
        CompareOverlayRenderer.show("v0001", "v0002", List.of(sampleEntry()), false);

        assertTrue(CompareOverlayRenderer.visible());
        assertFalse(CompareOverlayRenderer.xrayEnabled());

        CompareOverlayRenderer.setXrayEnabled(true);

        assertTrue(CompareOverlayRenderer.visible());
        assertTrue(CompareOverlayRenderer.xrayEnabled());

        CompareOverlayRenderer.setXrayEnabled(false);

        assertTrue(CompareOverlayRenderer.visible());
        assertFalse(CompareOverlayRenderer.xrayEnabled());
    }

    @Test
    void currentWorldOverlayRefreshKeepsVisibilityAndUpdatesTrackedDiff() {
        CompareOverlayRenderer.show("build-project", "v0001", "current", List.of(sampleEntry()), false);

        CompareOverlayRenderer.RefreshRequest request = CompareOverlayRenderer.refreshRequest();
        assertNotNull(request);
        assertTrue(request.involvesCurrentWorld());
        assertTrue(request.visible());
        assertEquals("build-project", request.projectName());
        assertEquals(1, CompareOverlayRenderer.changedBlockCount());

        CompareOverlayRenderer.refresh("build-project", "v0001", "current", List.of(
                sampleEntry(),
                new DiffBlockEntry(new BlockPoint(11, 64, 10), "minecraft:air", "minecraft:glass", ChangeType.ADDED)
        ), false);

        assertTrue(CompareOverlayRenderer.visible());
        assertEquals(2, CompareOverlayRenderer.changedBlockCount());
    }

    @Test
    void overlayDataMatchingIncludesProjectAndResolvedReferences() {
        CompareOverlayRenderer.show("project-a", "v0001", "v0002", List.of(sampleEntry()), false);

        assertTrue(CompareOverlayRenderer.hasDataFor("project-a", "v0001", "v0002"));
        assertTrue(CompareOverlayRenderer.visibleFor("project-a", "v0001", "v0002"));
        assertFalse(CompareOverlayRenderer.hasDataFor("project-a", "v0002", "v0003"));
        assertFalse(CompareOverlayRenderer.hasDataFor("project-b", "v0001", "v0002"));

        CompareOverlayRenderer.toggleVisibility();

        assertTrue(CompareOverlayRenderer.hasDataFor("project-a", "v0001", "v0002"));
        assertFalse(CompareOverlayRenderer.visibleFor("project-a", "v0001", "v0002"));
    }

    @Test
    void largeOverlaysAreMarkedForBackgroundPreparation() {
        assertFalse(CompareOverlayRenderer.shouldPrepareInBackground(lineEntries(CompareOverlayRenderer.DETAILED_DIFF_RENDER_LIMIT)));
        assertTrue(CompareOverlayRenderer.shouldPrepareInBackground(lineEntries(CompareOverlayRenderer.DETAILED_DIFF_RENDER_LIMIT + 1)));
    }

    @Test
    void smallChangedOverlayBuildsSingleCachedSection() {
        CompareOverlayRenderer.show("v0001", "v0002", List.of(sampleEntry()), false);

        assertTrue(CompareOverlayRenderer.visible());
        assertEquals(1, CompareOverlayRenderer.changedBlockCount());
        assertEquals(1, CompareOverlayRenderer.visibleSurfaceBlockCountForTest(10.5D, 64.5D, 10.5D));
        assertEquals(0, CompareOverlayRenderer.visibleVolumeBoxCountForTest(10.5D, 64.5D, 10.5D));
        assertEquals(1, CompareOverlayRenderer.meshSectionCountForTest());
        assertEquals(1, CompareOverlayRenderer.meshPrimitiveCountForTest());
        assertEquals(1, CompareOverlayRenderer.visibleMeshSectionCountForTest(10.5D, 64.5D, 10.5D, 1));
        assertEquals(0, CompareOverlayRenderer.visibleMeshSectionCountForTest(1024.0D, 64.5D, 1024.0D, 1));
    }

    @Test
    void largeChangedVolumeUsesExposedSurfaceMeshes() {
        CompareOverlayRenderer.show("v0001", "v0002", denseCubeEntries(), false);

        assertTrue(CompareOverlayRenderer.visible());
        assertEquals(8000, CompareOverlayRenderer.changedBlockCount());
        assertEquals(2168, CompareOverlayRenderer.visibleSurfaceBlockCountForTest(10.5D, 70.5D, 10.5D));
        assertEquals(0, CompareOverlayRenderer.visibleVolumeBoxCountForTest(10.5D, 70.5D, 10.5D));
        assertEquals(8, CompareOverlayRenderer.meshSectionCountForTest());
        assertEquals(2168, CompareOverlayRenderer.meshPrimitiveCountForTest());
    }

    @Test
    void belowCapCompareSurfaceMeshIsChunkedAndRenderDistanceCulled() {
        int sizeX = 64;
        int sizeY = 12;
        int sizeZ = 64;

        CompareOverlayRenderer.show("v0001", "v0002", cuboidEntries(sizeX, sizeY, sizeZ, 64), false);

        int expectedSurfaceBlocks = exposedShellBlockCount(sizeX, sizeY, sizeZ);
        assertTrue(CompareOverlayRenderer.visible());
        assertEquals(sizeX * sizeY * sizeZ, CompareOverlayRenderer.changedBlockCount());
        assertEquals(expectedSurfaceBlocks, CompareOverlayRenderer.visibleSurfaceBlockCountForTest(8.5D, 80.5D, 8.5D));
        assertEquals(0, CompareOverlayRenderer.visibleVolumeBoxCountForTest(8.5D, 80.5D, 8.5D));
        assertEquals(16, CompareOverlayRenderer.meshSectionCountForTest());
        assertEquals(expectedSurfaceBlocks, CompareOverlayRenderer.meshPrimitiveCountForTest());
        assertEquals(16, CompareOverlayRenderer.visibleMeshSectionCountForTest(8.5D, 72.5D, 8.5D, 0));
        assertEquals(0, CompareOverlayRenderer.visibleMeshSectionCountForTest(2048.0D, 80.5D, 2048.0D, 0));
    }

    @Test
    void justOverCapCompareOverlayFallsBackToMergedVolumeMesh() {
        int changedBlockCount = CompareOverlayRenderer.DETAILED_DIFF_RENDER_LIMIT + 1;

        CompareOverlayRenderer.show("v0001", "v0002", lineEntries(changedBlockCount), false);

        int volumeBoxCount = CompareOverlayRenderer.visibleVolumeBoxCountForTest(8.5D, 80.5D, 8.5D);
        assertTrue(CompareOverlayRenderer.visible());
        assertEquals(changedBlockCount, CompareOverlayRenderer.changedBlockCount());
        assertEquals(0, CompareOverlayRenderer.visibleSurfaceBlockCountForTest(8.5D, 80.5D, 8.5D));
        assertTrue(volumeBoxCount > 1);
        assertTrue(volumeBoxCount <= OverlayVolumeMerger.MAX_MERGED_BOXES);
        assertEquals(volumeBoxCount, CompareOverlayRenderer.meshSectionCountForTest());
        assertEquals(volumeBoxCount, CompareOverlayRenderer.meshPrimitiveCountForTest());
        int visibleNearCamera = CompareOverlayRenderer.visibleMeshSectionCountForTest(8.5D, 80.5D, 8.5D, 0);
        assertTrue(visibleNearCamera > 0);
        assertTrue(visibleNearCamera < volumeBoxCount);
        assertEquals(0, CompareOverlayRenderer.visibleMeshSectionCountForTest(2048.0D, 80.5D, 2048.0D, 0));
    }

    @Test
    void overCapDenseCompareOverlayUsesTiledCoarseVolumeMesh() {
        CompareOverlayRenderer.show("v0001", "v0002", cuboidEntries(80, 64, 64, 64), false);

        int volumeBoxCount = CompareOverlayRenderer.visibleVolumeBoxCountForTest(8.5D, 80.5D, 8.5D);
        assertTrue(CompareOverlayRenderer.visible());
        assertEquals(327_680, CompareOverlayRenderer.changedBlockCount());
        assertEquals(0, CompareOverlayRenderer.visibleSurfaceBlockCountForTest(8.5D, 80.5D, 8.5D));
        assertTrue(volumeBoxCount > 1);
        assertTrue(volumeBoxCount <= OverlayVolumeMerger.MAX_MERGED_BOXES);
        assertEquals(volumeBoxCount, CompareOverlayRenderer.meshPrimitiveCountForTest());
    }

    private static DiffBlockEntry sampleEntry() {
        return new DiffBlockEntry(new BlockPoint(10, 64, 10), "minecraft:stone", "minecraft:glass", ChangeType.CHANGED);
    }

    private static List<DiffBlockEntry> denseCubeEntries() {
        return cuboidEntries(20, 20, 20, 60);
    }

    private static List<DiffBlockEntry> cuboidEntries(int sizeX, int sizeY, int sizeZ, int minY) {
        List<DiffBlockEntry> entries = new ArrayList<>();
        for (int x = 0; x < sizeX; x++) {
            for (int y = minY; y < minY + sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    entries.add(new DiffBlockEntry(
                            new BlockPoint(x, y, z),
                            "minecraft:stone",
                            "minecraft:glass",
                            ChangeType.CHANGED
                    ));
                }
            }
        }
        return List.copyOf(entries);
    }

    private static List<DiffBlockEntry> lineEntries(int count) {
        List<DiffBlockEntry> entries = new ArrayList<>(count);
        for (int x = 0; x < count; x++) {
            entries.add(new DiffBlockEntry(
                    new BlockPoint(x, 64, 0),
                    "minecraft:stone",
                    "minecraft:glass",
                    ChangeType.CHANGED
            ));
        }
        return List.copyOf(entries);
    }

    private static int exposedShellBlockCount(int sizeX, int sizeY, int sizeZ) {
        return (sizeX * sizeY * sizeZ) - ((sizeX - 2) * (sizeY - 2) * (sizeZ - 2));
    }
}
