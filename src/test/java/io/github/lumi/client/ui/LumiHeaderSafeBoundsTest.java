package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LumiHeaderSafeBoundsTest {
    @Test
    void reservesSpaceBeforeTheGlobalTopRightControl() {
        for (int screenWidth : new int[] {320, 427, 640}) {
            int contentLeft = 36;
            int contentRight = screenWidth - 36;
            int safeRight = screenWidth - 44;
            assertEquals(safeRight - contentLeft,
                    LumiLegacyModalScreen.headerTextWidth(
                            screenWidth, contentLeft, contentRight));

            int centerX = screenWidth / 2;
            int centeredWidth = LumiLegacyModalScreen.centeredHeaderTextWidth(
                    screenWidth, centerX, contentLeft, contentRight);
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
