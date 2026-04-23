package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
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

    private static DiffBlockEntry sampleEntry() {
        return new DiffBlockEntry(new BlockPoint(10, 64, 10), "minecraft:stone", "minecraft:glass", ChangeType.CHANGED);
    }
}
