package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZoneDetailsScreenTest {
    @Test
    void keepsZoneFormToolbarAndHistoryInsideSupportedViewports() {
        int[][] viewports = {{320, 180}, {427, 240}, {640, 360}};
        int[] expectedRows = {1, 2, 5};
        for (int index = 0; index < viewports.length; index++) {
            LegacyWorkspaceLayout layout = LegacyWorkspaceLayout.fit(
                    viewports[index][0], viewports[index][1]);
            var geometry = LumiZoneDetailsScreen.zoneDetailsGeometry(
                    layout.contentWidth(), layout.windowHeight());

            assertTrue(geometry.messageWidth() > 0);
            assertTrue(geometry.tagsWidth() > 0);
            assertTrue(geometry.searchWidth() > 0);
            int lowerHeaderY = geometry.showSummary() ? geometry.summaryY()
                    : geometry.showStatus() ? geometry.statusY()
                    : geometry.titleY();
            assertTrue(lowerHeaderY + 9 <= geometry.messageFieldY());
            if (geometry.stacked()) {
                assertTrue(geometry.messageFieldY() + 20
                        <= geometry.actionY());
            } else {
                assertTrue(geometry.messageX() + geometry.messageWidth() + 6
                        <= geometry.innerRight() - 188);
            }
            assertTrue(geometry.actionY() + 18 <= geometry.tagsFieldY());
            assertTrue(geometry.tagsFieldY() + 20 <= geometry.tabsY());
            assertTrue(geometry.tabsY() + 18 <= geometry.toolbarY());
            assertTrue(geometry.toolbarY() + 20 <= geometry.historyY());
            assertEquals(layout.windowHeight(),
                    geometry.historyY() + geometry.historyHeight());
            assertEquals(expectedRows[index],
                    LumiZoneDetailsScreen.visibleHistoryRows(geometry, 10));
            assertTrue(geometry.historyY()
                    + (expectedRows[index] - 1) * geometry.rowStride()
                    + geometry.rowHeight() <= layout.windowHeight());
            assertTrue(expectedRows[index] * LumiHistoryGraphView.ROW_HEIGHT
                    <= geometry.historyHeight());
            assertTrue(geometry.messageX() + geometry.messageWidth()
                    <= geometry.innerRight());
            assertTrue(geometry.tagsX() + geometry.tagsWidth() + 4
                    <= geometry.innerRight() - 26);
            assertTrue(geometry.cardTextX() + geometry.cardTextWidth() + 4
                    <= geometry.cardActionX());
            assertTrue(geometry.cardActionX() >= geometry.innerLeft());
            assertTrue(geometry.cardActionsRight()
                    <= geometry.innerRight() - 6);
        }

        assertTrue(zoneGeometry(320, 180).stacked());
        assertTrue(zoneGeometry(427, 240).stacked());
        assertFalse(zoneGeometry(640, 360).stacked());
    }

    @Test
    void exposesLegacyZoneAmendAndSeeChangesActions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiZoneDetailsScreen.java"));

        assertTrue(source.contains("luma.action.amend_version"));
        assertTrue(source.contains("\"see-changes\""));
        assertTrue(source.contains("showChanges.run()"));
        assertTrue(source.contains("luma.history.view_cards"));
        assertTrue(source.contains("luma.history.view_graph"));
        assertTrue(source.contains("actions.openDetails()"));
        assertTrue(source.contains("actions.createBranch()"));
        assertTrue(source.contains("extends LumiLegacyPageScreen"));
        assertTrue(source.contains("zoneHistory.selectBranch(branch)"));
        assertTrue(source.contains("LumiLegacyButton tab = addLegacyContentButton("));
        assertTrue(source.contains("\"folder\""));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("zoneStatistics()"));
        assertTrue(source.contains("PendingStatisticsText::summary"));
        assertTrue(source.contains("\"tags\""));
        assertTrue(source.contains("actions.updateTags().accept"));
        assertTrue(source.contains("optimisticTags"));
        assertTrue(source.contains("new LumiVersionTagsScreen("));
        assertTrue(source.contains("new LumiCommitCard("));
        assertTrue(source.contains("historyCardLayout(rowY)"));
        assertTrue(source.contains("snapshot.head().equals(version.id())"));
        assertTrue(source.contains("clippedCenteredHeader("));

        int restore = source.indexOf("luma.action.restore");
        int open = source.indexOf("luma.action.open_details", restore);
        int branch = source.indexOf("luma.action.create_idea", open);
        int tags = source.indexOf("luma.action.edit_tags", branch);
        assertTrue(restore < open && open < branch && branch < tags);
    }

    private static LumiZoneDetailsScreen.ZoneDetailsGeometry zoneGeometry(
            int width, int height) {
        LegacyWorkspaceLayout layout = LegacyWorkspaceLayout.fit(width, height);
        return LumiZoneDetailsScreen.zoneDetailsGeometry(
                layout.contentWidth(), layout.windowHeight());
    }
}
