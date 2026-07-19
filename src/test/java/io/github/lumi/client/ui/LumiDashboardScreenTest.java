package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDashboardScreenTest {
    @Test
    void exposesHistoryAtDefaultClientGameTestViewport() {
        LegacyWorkspaceLayout layout = LegacyWorkspaceLayout.fit(427, 240);

        assertEquals(1, LumiDashboardScreen.visibleHistoryRows(
                layout.bodyHeight() - LumiDashboardScreen.HISTORY_TOP_OFFSET, 2));
    }

    @Test
    void restoresLegacyActionsAndCompactIconNavigation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));

        assertTrue(source.contains("luma.action.save_build"));
        assertTrue(source.contains("luma.action.amend_version"));
        assertTrue(source.contains("luma.action.see_changes"));
        assertTrue(source.contains("new EditBox("));
        assertTrue(source.contains("historyView.filtered("));
        assertTrue(source.contains("visibleVersions()"));
        assertTrue(source.contains("HistoryViewController.Mode.CARDS"));
        assertTrue(source.contains("HistoryViewController.Mode.GRAPH"));
        assertTrue(source.contains("new LumiHistoryGraphView("));
        assertTrue(source.contains("graphView.renderHover("));
        assertTrue(source.contains("new LumiComparePickerScreen("));
        assertTrue(source.contains("luma.tab.variants"));
        assertTrue(source.contains("luma.action.settings"));
        assertTrue(source.contains("luma.action.buy_me_a_coffee"));
        assertTrue(source.contains("luma.action.paypal_donate"));
        assertTrue(source.contains("luma.action.report_bug"));
        assertTrue(source.contains("luma.window.support"));
        assertTrue(source.contains("addButton(x, y + 132, width, \"luma.action.more\""));
        assertTrue(source.contains("addCompactSidebarButtons"));
        assertTrue(source.contains("activeZoneColor()"));
        assertTrue(source.contains(
                "activeZoneColor().orElse(LegacyLumiTheme.WINDOW_BORDER)"));
        assertTrue(source.contains("\"rollback\", \"luma.action.restore\""));
        assertTrue(source.contains("\"folder\", \"luma.action.open_details\""));
        assertTrue(source.contains("\"tags\""));
        assertTrue(source.contains("\"branch\", \"luma.action.create_idea\""));
        assertTrue(source.contains("version.statistics().blocks()"));
        assertTrue(source.contains("previews.texture(snapshot.dimensionId(), version.id())"));
        assertTrue(source.contains("NO_PREVIEW_ICON"));
        assertTrue(source.contains(
                "pagedHistory.ensurePageSize(HistoryPagePayload.MAX_VERSIONS)"));
        assertTrue(source.contains("pagedHistory.next()"));
        assertFalse(source.contains("addHistoryPageButtons"));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("addBranchTabs()"));
        assertTrue(source.contains("pagedHistory.selectBranch(branch)"));
        assertTrue(source.contains("latestCreated()"));
        assertTrue(source.contains("snapshot.head().equals(version.id())"));
        assertTrue(source.contains("updateTags.accept(editingTags, replacement)"));
        assertTrue(source.contains("!Objects.equals(renderedPage, latestPage)"));
        assertTrue(source.contains(
                "PendingStatisticsText.summary(result.workspace())"));
        assertTrue(source.contains("requestPendingStatistics.run()"));
    }

    @Test
    void defersSearchRebuildUntilAfterTheInputEvent() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));
        int start = source.indexOf("private void search(String value)");
        int end = source.indexOf("private void addBranchTabs()", start);
        String searchMethod = source.substring(start, end);

        assertTrue(searchMethod.contains("searchResultsDirty = true;"));
        assertFalse(searchMethod.contains("rebuildWidgets()"));
        assertTrue(source.contains("|| searchResultsDirty"));
    }
}
