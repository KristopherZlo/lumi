package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiZoneDetailsScreenTest {
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
        assertTrue(source.contains("\"folder\""));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("zoneStatistics()"));
        assertTrue(source.contains("PendingStatisticsText::summary"));
        assertTrue(source.contains("\"tags\""));
        assertTrue(source.contains("actions.updateTags().accept"));
        assertTrue(source.contains("optimisticTags"));
        assertTrue(source.contains("new LumiVersionTagsScreen("));

        int restore = source.indexOf("luma.action.restore");
        int open = source.indexOf("luma.action.open_details", restore);
        int branch = source.indexOf("luma.action.create_idea", open);
        int tags = source.indexOf("luma.action.edit_tags", branch);
        assertTrue(restore < open && open < branch && branch < tags);
    }
}
