package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDashboardScreenTest {
    @Test
    void restoresLegacyActionsAndCompactIconNavigation() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDashboardScreen.java"));

        assertTrue(source.contains("luma.action.save_build"));
        assertTrue(source.contains("luma.action.amend_version"));
        assertTrue(source.contains("luma.action.see_changes"));
        assertTrue(source.contains("new EditBox("));
        assertTrue(source.contains("searchController.filter("));
        assertTrue(source.contains("visibleVersions()"));
        assertTrue(source.contains("luma.tab.variants"));
        assertTrue(source.contains("luma.action.settings"));
        assertTrue(source.contains("addCompactSidebarButtons"));
        assertTrue(source.contains("\"rollback\", \"luma.action.restore\""));
        assertTrue(source.contains("\"trash\", \"luma.action.delete\""));
        assertTrue(source.contains("previews.texture(snapshot.dimensionId(), version.id())"));
        assertTrue(source.contains("NO_PREVIEW_ICON"));
        assertTrue(source.contains("if (!Objects.equals(snapshot, latest))"));
    }
}
