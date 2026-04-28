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
    void denseChangedVolumeStillExposesRenderableSurfaceBlocks() {
        CompareOverlayRenderer.show("v0001", "v0002", denseCubeEntries(), false);

        assertTrue(CompareOverlayRenderer.visible());
        assertEquals(8000, CompareOverlayRenderer.changedBlockCount());
        assertTrue(CompareOverlayRenderer.visibleSurfaceBlockCountForTest(10.5D, 70.5D, 10.5D) > 0);
    }

    private static DiffBlockEntry sampleEntry() {
        return new DiffBlockEntry(new BlockPoint(10, 64, 10), "minecraft:stone", "minecraft:glass", ChangeType.CHANGED);
    }

    private static List<DiffBlockEntry> denseCubeEntries() {
        List<DiffBlockEntry> entries = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            for (int y = 60; y < 80; y++) {
                for (int z = 0; z < 20; z++) {
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
}
