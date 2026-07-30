package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiComparePickerScreenTest {
    @Test
    void keepsIndependentColumnsAndDispatchesTheEyeIntoTheWorld()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiComparePickerScreen.java"));

        assertTrue(source.contains("leftHistory"));
        assertTrue(source.contains("rightHistory"));
        assertTrue(source.contains("ClientHistoryPageStore.createChannel()"));
        assertTrue(source.contains(
                "leftHistory.ensurePageSize(HistoryPagePayload.MAX_VERSIONS)"));
        assertTrue(source.contains(
                "rightHistory.ensurePageSize(HistoryPagePayload.MAX_VERSIONS)"));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("history(left).loadNextPage()"));
        assertTrue(source.contains("leftSelection"));
        assertTrue(source.contains("rightSelection"));
        assertTrue(source.contains("LumiDropdown.branches("));
        assertTrue(source.contains("history(left).selectBranch(branch)"));
        assertTrue(source.contains("\"textures/gui/icons/see-changes.png\""));
        assertTrue(source.contains("\"eye-open\""));
        assertTrue(source.contains(
                "target().isPresent() || highlightVisible.getAsBoolean()"));
        assertTrue(source.contains("toggleHighlight.run()"));
        assertTrue(source.contains(
                "layout.x() + layout.width() - 42, footerY"));
        assertTrue(source.contains("minecraft.setScreen(null)"));
        assertTrue(source.contains(
                "selected ? LumiTheme.ACCENT : LumiTheme.PANEL_BORDER"));
        assertTrue(source.contains("snapshot.dimensionId(), version.id()"));
    }

    @Test
    void narrowColumnsKeepBothCardsInsideThePage() {
        assertEquals(205, LumiComparePickerScreen.columnWidth(484));
        assertEquals(108, LumiComparePickerScreen.columnWidth(271));
        assertEquals(55, LumiComparePickerScreen.columnWidth(164));
    }

    @Test
    void everySupportedViewportKeepsSelectableRowsAboveTheFooter() {
        int[] heights = {160, 220, 340, 500};
        int[] expectedRows = {1, 2, 5, 8};
        for (int index = 0; index < heights.length; index++) {
            int rows = LumiComparePickerScreen.visibleRows(heights[index]);

            assertEquals(expectedRows[index], rows);
            assertTrue(86 + (rows - 1) * 46 + 42 <= heights[index] - 28);
        }
    }
}
