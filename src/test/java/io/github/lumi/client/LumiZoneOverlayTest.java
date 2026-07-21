package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.ZoneShellFace;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZoneOverlayTest {
    @Test
    void mapsEveryShellOrientationToBlockCoordinates() {
        var east = LumiZoneOverlay.box(new ZoneShellFace(
                ZoneShellFace.Side.EAST, 32, 16, 48, -16, 0));
        var up = LumiZoneOverlay.box(new ZoneShellFace(
                ZoneShellFace.Side.UP, 64, 16, 48, -16, 0));

        assertEquals(32, east.getCenter().x, 0.001);
        assertEquals(16, east.minY, 0.001);
        assertEquals(64, up.getCenter().y, 0.001);
        assertEquals(16, up.minX, 0.001);
    }

    @Test
    void focusedActiveEnteredAndOtherZonesRemainDistinct() {
        int color = 0xff336699;

        int focused = LumiZoneOverlay.renderColor(
                color, LumiZoneOverlay.Mode.FOCUSED, false, false);
        int active = LumiZoneOverlay.renderColor(
                color, LumiZoneOverlay.Mode.ALL, true, false);
        int entered = LumiZoneOverlay.renderColor(
                color, LumiZoneOverlay.Mode.ALL, false, true);
        int other = LumiZoneOverlay.renderColor(
                color, LumiZoneOverlay.Mode.ALL, false, false);

        assertNotEquals(focused, active);
        assertNotEquals(active, entered);
        assertNotEquals(entered, other);
    }

    @Test
    void finishesFillsBeforeAcquiringTheOutlineBuffer() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiZoneOverlay.java"));
        int fillBuffer = source.indexOf("LumiCompareRenderTypes.fill(false)");
        int fill = source.indexOf("renderFace(", fillBuffer);
        int outlineBuffer = source.indexOf(
                "LumiCompareRenderTypes.outline(false)", fillBuffer);

        assertTrue(fillBuffer < fill);
        assertTrue(fill < outlineBuffer);
    }
}
