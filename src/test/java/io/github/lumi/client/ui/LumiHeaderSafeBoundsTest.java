package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LumiHeaderSafeBoundsTest {
    @Test
    void reservesSpaceBeforeTheFrameAlignedTopRightControl() {
        for (int screenWidth : new int[] {320, 427, 640}) {
            int frameX = 24;
            int frameWidth = screenWidth - 48;
            int controlX = LumiLegacyModalScreen.navigationControlX(
                    frameX, frameWidth);
            int contentLeft = frameX + 12;
            int contentRight = frameX + frameWidth - 12;
            int safeRight = controlX - 8;
            assertEquals(frameX + frameWidth - 8 - 26, controlX);
            assertEquals(safeRight - contentLeft,
                    LumiLegacyModalScreen.headerTextWidth(
                            controlX, contentLeft, contentRight));

            int centerX = frameX + frameWidth / 2;
            int centeredWidth = LumiLegacyModalScreen.centeredHeaderTextWidth(
                    controlX, centerX, contentLeft, contentRight);
            assertTrue(centerX + centeredWidth / 2 <= safeRight);
            assertTrue(centerX - centeredWidth / 2 >= contentLeft);
        }
    }

    @Test
    void affectedScreensClipHeadersThroughTheSharedBound() throws Exception {
        Map<String, String> expectedCalls = Map.of(
                "LumiZoneRestoreScreen.java", "clippedCenteredHeader(",
                "LumiDeleteVersionScreen.java", "clippedCenteredHeader(",
                "LumiDeleteZoneScreen.java", "clippedCenteredHeader(",
                "LumiRestoreScreen.java", "clippedCenteredHeader(",
                "LumiBranchSlotScreen.java", "clippedCenteredHeader(",
                "LumiMergeScreen.java", "clippedCenteredHeader(",
                "LumiBranchScreen.java", "clippedHeader(",
                "LumiDimensionHistoryScreen.java", "clippedHeader(");
        Path screens = Path.of("src/main/java/io/github/lumi/client/ui");
        for (Map.Entry<String, String> expected : expectedCalls.entrySet()) {
            assertTrue(Files.readString(screens.resolve(expected.getKey()))
                    .contains(expected.getValue()), expected.getKey());
        }
    }
}
