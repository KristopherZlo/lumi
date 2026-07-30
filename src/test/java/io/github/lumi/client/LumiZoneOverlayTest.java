package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.ZoneShellFace;
import io.github.lumi.network.ZoneOverlayArgument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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

    @Test
    void jebZoneCyclesWithoutChangingOrdinaryZoneColors() {
        LumiZoneColor colors = new LumiZoneColor();
        int persisted = 0xff336699;

        assertEquals(persisted, colors.resolve("builder", persisted, 1_000));
        assertNotEquals(
                colors.resolve("jeb_", persisted, 0),
                colors.resolve("jeb_", persisted, 1_000));
        assertEquals(0xff000000,
                colors.resolve("jeb_", persisted, 2_000) & 0xff000000);
    }

    @Test
    void requestsOnlyAfterTheSameCellIsStableForFourTicks() {
        List<ZoneOverlayArgument.Mode> requests = new ArrayList<>();
        LumiZoneOverlay overlay = overlay(requests);

        for (int cell = 0; cell < 100; cell++) {
            overlay.considerRequest(key(cell, LumiZoneOverlay.Mode.FOCUSED), false);
        }
        assertTrue(requests.isEmpty());

        var stable = key(100, LumiZoneOverlay.Mode.FOCUSED);
        for (int tick = 0; tick < 4; tick++) {
            overlay.considerRequest(stable, false);
        }
        assertEquals(List.of(ZoneOverlayArgument.Mode.FOCUSED), requests);

        for (int tick = 0; tick < 10; tick++) {
            overlay.considerRequest(stable, false);
        }
        assertEquals(1, requests.size());
    }

    @Test
    void changingModeReplacesThePreviousCandidate() {
        List<ZoneOverlayArgument.Mode> requests = new ArrayList<>();
        LumiZoneOverlay overlay = overlay(requests);
        var focused = key(0, LumiZoneOverlay.Mode.FOCUSED);
        for (int tick = 0; tick < 3; tick++) {
            overlay.considerRequest(focused, false);
        }

        overlay.cycle();
        var all = key(0, LumiZoneOverlay.Mode.ALL);
        for (int tick = 0; tick < 3; tick++) {
            overlay.considerRequest(all, false);
        }
        assertTrue(requests.isEmpty());
        overlay.considerRequest(all, false);
        assertEquals(List.of(ZoneOverlayArgument.Mode.ALL), requests);
    }

    private static LumiZoneOverlay overlay(
            List<ZoneOverlayArgument.Mode> requests) {
        return new LumiZoneOverlay(
                new io.github.lumi.client.state.ClientZoneOverlayStore(),
                new io.github.lumi.client.state.ClientHistoryStore(),
                requests::add);
    }

    private static LumiZoneOverlay.RequestKey key(
            int cell, LumiZoneOverlay.Mode mode) {
        return new LumiZoneOverlay.RequestKey(
                "minecraft:overworld", new UUID(0, 1), mode,
                cell, 0, 0, List.of());
    }
}
