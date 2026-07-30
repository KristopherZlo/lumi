package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDashboardScreenTest {
    @Test
    void keepsDashboardBandsSeparatedAtReferenceAndSmallViewports() {
        LumiPageLayout reference = LumiPageLayout.fit(640, 360);
        var referenceGeometry = LumiDashboardScreen.dashboardGeometry(
                reference.bodyY(), reference.bodyHeight(), reference.bodyWidth(), 0);
        LumiPageLayout small = LumiPageLayout.fit(427, 240);
        var smallGeometry = LumiDashboardScreen.dashboardGeometry(
                small.bodyY(), small.bodyHeight(), small.bodyWidth(), 0);

        assertEquals(5, referenceGeometry.latestY()
                - (reference.bodyY() + referenceGeometry.buildPanelHeight()));
        assertEquals(5, referenceGeometry.historyY()
                - (referenceGeometry.latestY() + referenceGeometry.latestHeight()));
        assertEquals(22, LumiDashboardScreen.latestCardY(referenceGeometry)
                - referenceGeometry.latestY());
        assertEquals(6, referenceGeometry.latestHeight()
                - 22 - LumiDashboardScreen.historyRowHeight(reference.bodyWidth()));
        assertEquals(0, smallGeometry.latestHeight());
        assertEquals(reference.bodyY() + reference.bodyHeight(),
                referenceGeometry.historyY() + referenceGeometry.historyHeight());
        assertEquals(3, LumiDashboardScreen.visibleHistoryRows(
                referenceGeometry.historyHeight(), 10, reference.bodyWidth()));
        assertEquals(1, LumiDashboardScreen.visibleHistoryRows(
                smallGeometry.historyHeight(), 2, small.bodyWidth()));
    }

    @Test
    void omitsLatestAndGivesItsSpaceToHistoryWithoutUserSaves() {
        LumiPageLayout layout = LumiPageLayout.fit(640, 360);
        var geometry = LumiDashboardScreen.dashboardGeometry(
                layout.bodyY(), layout.bodyHeight(), layout.bodyWidth(), 0, false);

        assertFalse(geometry.latestVisible());
        assertEquals(geometry.latestY(), geometry.historyY());
        assertEquals(layout.bodyY() + layout.bodyHeight(),
                geometry.historyY() + geometry.historyHeight());
    }

    @Test
    void stacksHistoryActionsBelowTextWhenTheContentPaneIsVeryNarrow() {
        LumiPageLayout tiny = LumiPageLayout.fit(320, 240);
        int bodyX = tiny.bodyX();
        int bodyWidth = tiny.bodyWidth();

        assertTrue(LumiDashboardScreen.compactHistoryCards(bodyWidth));
        assertEquals(54, LumiDashboardScreen.historyRowHeight(bodyWidth));
        assertTrue(LumiDashboardScreen.historyTextWidth(bodyWidth) > 0);
        LumiCommitCard.Layout compactCard = LumiDashboardScreen.versionCardLayout(
                bodyX, bodyWidth, 100);
        assertTrue(compactCard.actionX() >= compactCard.x() + 6);
        assertTrue(LumiDashboardScreen.historyActionX(bodyX, bodyWidth, 3) + 26
                <= compactCard.right() - 6);
        assertTrue(LumiDashboardScreen.historyActionY(100, bodyWidth) + 18
                <= compactCard.bottom() - 6);

        LumiPageLayout wide = LumiPageLayout.fit(640, 360);
        LumiCommitCard.Layout wideCard = LumiDashboardScreen.versionCardLayout(
                wide.bodyX(), wide.bodyWidth(), 100);
        assertEquals(wideCard.right() - 6,
                LumiDashboardScreen.historyActionX(
                        wide.bodyX(), wide.bodyWidth(), 3) + 26);
    }

    @Test
    void rotatesCardActionsAroundTheCardCenter() {
        LumiCommitCard.Layout card = LumiDashboardScreen.versionCardLayout(
                40, 300, 100);
        int actionX = card.actionX(0);
        int actionY = card.actionY();
        int rotatedX = card.rotatedX(actionX, 26);
        int rotatedY = card.rotatedY(actionY, 18);

        assertEquals(card.x() + card.right(), actionX + 26 + rotatedX);
        assertEquals(card.y() + card.bottom(), actionY + 18 + rotatedY);
        assertEquals(actionX, card.rotatedX(rotatedX, 26));
        assertEquals(actionY, card.rotatedY(rotatedY, 18));
    }

    @Test
    void compactNavigationStaysAboveTheSupportPanel() {
        LumiPageLayout tiny = LumiPageLayout.fit(320, 180);
        int supportTop = LumiPageScreen.supportTop(tiny);

        for (int index = 0; index < 6; index++) {
            int x = LumiPageScreen.compactSidebarActionX(tiny, index);
            int y = LumiPageScreen.compactSidebarActionY(tiny, index);
            assertTrue(x >= tiny.windowX() + 10);
            assertTrue(x + LumiPageScreen.compactSidebarActionWidth(tiny)
                    <= tiny.contentX() - 10);
            assertTrue(y + 18 <= supportTop);
        }
        assertTrue(LumiPageScreen.supportCreditY(tiny) + 11
                < tiny.windowY() + tiny.windowHeight());
        assertTrue(LumiPageScreen.supportCreditY(tiny) - 4
                - (supportTop + 75) >= 8);
    }

    @Test
    void contextualHintPushesActionsWithoutCrossingDashboardBands() {
        LumiPageLayout layout = LumiPageLayout.fit(640, 360);
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
        LumiPageLayout layout = LumiPageLayout.fit(320, 200);
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
    void restoresV2ActionsAndCompactIconNavigation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));
        String pageSource = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiPageScreen.java"));
        String cardSource = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiCommitCard.java"));

        assertTrue(source.contains("luma.action.save_build"));
        assertTrue(source.contains("luma.action.amend_version"));
        assertTrue(source.contains("luma.action.see_changes"));
        assertTrue(source.contains("search = addTextField("));
        assertFalse(source.contains("historyView.filtered("));
        assertTrue(source.contains("historyVersions = pagedHistory.versions()"));
        assertTrue(source.contains("visibleVersions()"));
        assertTrue(source.contains("HistoryViewController.Mode.CARDS"));
        assertTrue(source.contains("HistoryViewController.Mode.GRAPH"));
        assertTrue(source.contains("new LumiHistoryGraphView("));
        assertTrue(source.contains("graphView.renderHover("));
        assertTrue(source.contains("new LumiComparePickerScreen("));
        assertTrue(pageSource.contains("luma.tab.variants"));
        assertTrue(pageSource.contains("luma.action.settings"));
        assertTrue(pageSource.contains("luma.action.buy_me_a_coffee"));
        assertTrue(pageSource.contains("luma.action.paypal_donate"));
        assertTrue(pageSource.contains("luma.action.report_bug"));
        assertTrue(pageSource.contains("luma.window.support"));
        assertTrue(pageSource.contains("luma.window.credit"));
        assertTrue(pageSource.contains("luma.window.mod_version"));
        assertTrue(pageSource.contains("projectContext(snapshot)"));
        assertFalse(pageSource.contains("luma.window.mode"));
        assertFalse(pageSource.contains("drawChip("));
        assertTrue(pageSource.contains("addSupportButton("));
        assertTrue(pageSource.contains("SIDEBAR_BUTTON_STRIDE * 5"));
        assertFalse(pageSource.contains(
                "\"luma.tab.import_export\", ProjectTab.IMPORT_EXPORT"));
        assertTrue(pageSource.contains("addCompactSidebarButtons"));
        assertTrue(pageSource.contains("activeZoneColor()"));
        assertTrue(pageSource.contains(
                "activeZoneColor().orElse(LumiTheme.WINDOW_BORDER)"));
        assertTrue(source.contains("\"rollback\", \"luma.action.restore\""));
        assertTrue(source.contains("\"folder\", \"luma.action.open_details\""));
        assertTrue(source.contains("\"tags\""));
        assertTrue(source.contains("\"branch\", \"luma.action.create_idea\""));
        assertTrue(cardSource.contains("version.statistics().blocks()"));
        assertTrue(cardSource.contains("previews.texture(dimensionId, version.id())"));
        assertTrue(cardSource.contains("luma.dashboard.latest_badge"));
        assertFalse(cardSource.contains("layout.x() + 3"));
        assertTrue(source.contains("new LumiCommitCard("));
        assertTrue(source.contains(
                "pagedHistory.ensurePageSize(HistoryPagePayload.MAX_VERSIONS)"));
        assertTrue(source.contains("pagedHistory.loadNextPage()"));
        assertFalse(source.contains("addHistoryPageButtons"));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("LumiDropdown.branches("));
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
        assertFalse(source.contains("renderedStatistics"));

        int restore = source.indexOf("\"rollback\", \"luma.action.restore\"");
        int open = source.indexOf("\"folder\", \"luma.action.open_details\"", restore);
        int branch = source.indexOf("\"branch\", \"luma.action.create_idea\"", open);
        int tags = source.indexOf("\"tags\", \"luma.action.edit_tags\"", branch);
        assertTrue(restore < open && open < branch && branch < tags);
    }

    @Test
    void pendingOnlySnapshotsDoNotRequireWidgetRebuild() {
        HistorySnapshotPayload before = snapshot('a', 1);

        assertTrue(LumiDashboardScreen.samePresentation(
                before, snapshot('a', 5)));
        assertFalse(LumiDashboardScreen.samePresentation(
                before, snapshot('b', 1)));
    }

    @Test
    void keepsSearchResultsUntilTheCorrelatedPageArrives() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));
        int start = source.indexOf("private void search(String value)");
        int end = source.indexOf("private void addBranchDropdown(", start);
        String searchMethod = source.substring(start, end);

        assertFalse(searchMethod.contains("rebuildWidgets()"));
        assertFalse(source.contains("searchResultsDirty"));
    }

    private static HistorySnapshotPayload snapshot(char head, int pending) {
        return new HistorySnapshotPayload(
                "minecraft:overworld",
                new CommitId(new ObjectId(String.valueOf(head).repeat(64))),
                0, pending, false);
    }
}
