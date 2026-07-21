package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZoneDetailsScreenTest {
    @Test
    void reusesTheDashboardHistoryGeometryAndCommitCards() throws Exception {
        String source = source();

        assertTrue(source.contains("LumiDashboardScreen.dashboardGeometry("));
        assertTrue(source.contains("LumiDashboardScreen.latestCardY(geometry)"));
        assertTrue(source.contains("LumiDashboardScreen.versionCardLayout("));
        assertTrue(source.contains("LumiDashboardScreen.visibleHistoryRows("));
        assertTrue(source.contains("LumiDashboardScreen.HISTORY_TOOLBAR_OFFSET"));
        assertTrue(source.contains("LumiDashboardScreen.HISTORY_FIRST_ROW_OFFSET"));
        assertTrue(source.contains("luma.dashboard.latest_badge"));
        assertTrue(source.contains("renderPageHeader("));
        assertTrue(source.contains("search = addTextField("));
        assertFalse(source.contains("clippedCenteredHeader("));
        assertFalse(source.contains("ZoneDetailsGeometry"));
    }

    @Test
    void exposesTheSameHistoryActionsWithZoneScopedSave() throws Exception {
        String source = source();

        assertTrue(source.contains("luma.zones.save_button"));
        assertTrue(source.contains("openSave"));
        assertTrue(source.contains("openAmend"));
        assertTrue(source.contains("luma.action.amend_version"));
        assertTrue(source.contains("\"see-changes\""));
        assertTrue(source.contains("showChanges.run()"));
        assertTrue(source.contains("luma.history.view_cards"));
        assertTrue(source.contains("luma.history.view_graph"));
        assertTrue(source.contains("actions.openDetails()"));
        assertTrue(source.contains("actions.createBranch()"));
        assertTrue(source.contains("zoneHistory.selectBranch(branch)"));
        assertTrue(source.contains("new LumiBranchDropdown("));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("zoneStatistics()"));
        assertTrue(source.contains("PendingStatisticsText::summary"));
        assertTrue(source.contains("actions.updateTags().accept"));
        assertTrue(source.contains("optimisticTags"));
        assertTrue(source.contains(
                "tags.active = !VersionText.immutable(version)"));
        assertTrue(source.contains("snapshot.head().equals(version.id())"));

        int restore = source.indexOf("luma.action.restore");
        int open = source.indexOf("luma.action.open_details", restore);
        int branch = source.indexOf("luma.action.create_idea", open);
        int tags = source.indexOf("luma.action.edit_tags", branch);
        assertTrue(restore < open && open < branch && branch < tags);
    }

    private static String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiZoneDetailsScreen.java"));
    }
}
