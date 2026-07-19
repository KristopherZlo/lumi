package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDashboardScreenTest {
    @Test
    void keepsDashboardBandsSeparatedAtReferenceAndSmallViewports() {
        LegacyWorkspaceLayout reference = LegacyWorkspaceLayout.fit(640, 360);
        var referenceGeometry = LumiDashboardScreen.dashboardGeometry(
                reference.bodyY(), reference.bodyHeight(), reference.bodyWidth(), 0);
        LegacyWorkspaceLayout small = LegacyWorkspaceLayout.fit(427, 240);
        var smallGeometry = LumiDashboardScreen.dashboardGeometry(
                small.bodyY(), small.bodyHeight(), small.bodyWidth(), 0);

        assertEquals(5, referenceGeometry.latestY()
                - (reference.bodyY() + referenceGeometry.buildPanelHeight()));
        assertEquals(5, referenceGeometry.historyY()
                - (referenceGeometry.latestY() + referenceGeometry.latestHeight()));
        assertEquals(LumiDashboardScreen.historyRowHeight(reference.bodyWidth()),
                referenceGeometry.latestHeight());
        assertEquals(LumiDashboardScreen.historyRowHeight(small.bodyWidth()),
                smallGeometry.latestHeight());
        assertEquals(reference.bodyY() + reference.bodyHeight(),
                referenceGeometry.historyY() + referenceGeometry.historyHeight());
        assertEquals(3, LumiDashboardScreen.visibleHistoryRows(
                referenceGeometry.historyHeight(), 10, reference.bodyWidth()));
        assertEquals(1, LumiDashboardScreen.visibleHistoryRows(
                smallGeometry.historyHeight(), 2, small.bodyWidth()));
    }

    @Test
    void stacksHistoryActionsBelowTextWhenTheContentPaneIsVeryNarrow() {
        LegacyWorkspaceLayout tiny = LegacyWorkspaceLayout.fit(320, 240);
        int bodyX = tiny.bodyX();
        int bodyWidth = tiny.bodyWidth();

        assertTrue(LumiDashboardScreen.compactHistoryCards(bodyWidth));
        assertEquals(54, LumiDashboardScreen.historyRowHeight(bodyWidth));
        assertTrue(LumiDashboardScreen.historyTextWidth(bodyWidth) > 0);
        assertTrue(LumiDashboardScreen.historyActionX(bodyX, bodyWidth, 0)
                >= bodyX + 6);
        assertTrue(LumiDashboardScreen.historyActionX(bodyX, bodyWidth, 3) + 26
                <= bodyX + bodyWidth - 12);
        assertTrue(LumiDashboardScreen.historyActionY(100, bodyWidth) + 18
                <= 100 + LumiDashboardScreen.historyRowHeight(bodyWidth));

        LegacyWorkspaceLayout wide = LegacyWorkspaceLayout.fit(640, 360);
        assertEquals(wide.bodyX() + wide.bodyWidth() - 12,
                LumiDashboardScreen.historyActionX(
                        wide.bodyX(), wide.bodyWidth(), 3) + 26);
    }

    @Test
    void contextualHintPushesActionsWithoutCrossingDashboardBands() {
        LegacyWorkspaceLayout layout = LegacyWorkspaceLayout.fit(640, 360);
        int hintHeight = 54;
        var geometry = LumiDashboardScreen.dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(), hintHeight);

        assertEquals(geometry.hintY() + hintHeight + 5, geometry.actionY());
        assertEquals(geometry.actionY() + 18 + 6,
                layout.bodyY() + geometry.buildPanelHeight());
        assertEquals(5, geometry.latestY()
                - (layout.bodyY() + geometry.buildPanelHeight()));
        assertTrue(geometry.historyHeight() >= 0);
    }

    @Test
    void hidesLowerBandsWhenTallHintConsumesTinyViewport() {
        LegacyWorkspaceLayout layout = LegacyWorkspaceLayout.fit(320, 200);
        int hintHeight = 70;
        var geometry = LumiDashboardScreen.dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(), hintHeight);
        int bodyBottom = layout.bodyY() + layout.bodyHeight();

        assertEquals(geometry.hintY() + hintHeight + 5, geometry.actionY());
        assertTrue(geometry.hintY() >= layout.bodyY());
        assertFalse(geometry.headerVisible());
        assertTrue(geometry.actionY() + 18 <= bodyBottom);
        assertEquals(bodyBottom,
                layout.bodyY() + geometry.buildPanelHeight());
        assertEquals(bodyBottom, geometry.latestY());
        assertEquals(0, geometry.latestHeight());
        assertEquals(bodyBottom, geometry.historyY());
        assertEquals(0, geometry.historyHeight());
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
        assertTrue(source.contains("addBranchTabs("));
        assertTrue(source.contains("addLegacyContentButton("));
        assertTrue(source.contains("pagedHistory.selectBranch(branch)"));
        assertTrue(source.contains("latestCreated()"));
        assertTrue(source.contains(
                "latestCreated().ifPresent(version -> addVersionActions("));
        assertTrue(source.contains(
                "latestCreated().ifPresent(version -> renderVersionCard("));
        assertTrue(source.contains("snapshot.head().equals(version.id())"));
        assertTrue(source.contains("new LumiVersionTagsScreen("));
        assertTrue(source.contains("updateTags.accept(version.id(), replacement)"));
        assertTrue(source.contains("!Objects.equals(renderedPage, latestPage)"));
        assertTrue(source.contains(
                "PendingStatisticsText.summary(result.workspace())"));
        assertTrue(source.contains("requestPendingStatistics.run()"));

        int restore = source.indexOf("\"rollback\", \"luma.action.restore\"");
        int open = source.indexOf("\"folder\", \"luma.action.open_details\"", restore);
        int branch = source.indexOf("\"branch\", \"luma.action.create_idea\"", open);
        int tags = source.indexOf("\"tags\", \"luma.action.edit_tags\"", branch);
        assertTrue(restore < open && open < branch && branch < tags);
    }

    @Test
    void defersSearchRebuildUntilAfterTheInputEvent() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));
        int start = source.indexOf("private void search(String value)");
        int end = source.indexOf("private void addBranchTabs(", start);
        String searchMethod = source.substring(start, end);

        assertTrue(searchMethod.contains("searchResultsDirty = true;"));
        assertFalse(searchMethod.contains("rebuildWidgets()"));
        assertTrue(source.contains("|| searchResultsDirty"));
    }
}
