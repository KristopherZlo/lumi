package io.github.luma.ui.overlay;

import io.github.luma.domain.model.BlockPoint;
import io.github.luma.domain.model.ChangeType;
import io.github.luma.domain.model.DiffBlockEntry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    private static DiffBlockEntry sampleEntry() {
        return new DiffBlockEntry(new BlockPoint(10, 64, 10), "minecraft:stone", "minecraft:glass", ChangeType.CHANGED);
    }
}
