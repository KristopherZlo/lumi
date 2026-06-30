package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCompareScreenTest {

    @Test
    void sidebarShowsCompareAboveImportExport() throws IOException {
        String navigation = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/navigation/ProjectSidebarNavigation.java"),
                StandardCharsets.UTF_8
        );
        String router = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/navigation/ScreenRouter.java"),
                StandardCharsets.UTF_8
        );
        String tabs = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/navigation/ProjectWorkspaceTab.java"),
                StandardCharsets.UTF_8
        );

        int compareIndex = navigation.indexOf("luma.tab.compare");
        int importExportIndex = navigation.indexOf("luma.tab.import_export");

        assertTrue(compareIndex >= 0, "Sidebar should expose the Compare workspace tab");
        assertTrue(compareIndex < importExportIndex, "Compare should sit above Import / Export");
        assertTrue(tabs.contains("COMPARE"));
        assertTrue(router.contains("openProjectCompare"));
    }

    @Test
    void comparePageSelectsTwoBranchSavesAndStartsOverlay() throws IOException {
        Path screenPath = Path.of("src/client/java/io/github/luma/ui/screen/ProjectCompareScreen.java");
        assertTrue(Files.exists(screenPath), "Compare workspace screen should exist");

        String source = Files.readString(screenPath, StandardCharsets.UTF_8);
        String sections = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectCompareScreenSections.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("ProjectWorkspaceTab.COMPARE"));
        assertTrue(source.contains("selectedLeftVariantId"));
        assertTrue(source.contains("selectedRightVariantId"));
        assertTrue(source.contains("selectedLeftVersionId"));
        assertTrue(source.contains("selectedRightVersionId"));
        assertTrue(sections.contains("see-changes.png"));
        assertTrue(source.contains("showOverlay"));
        assertTrue(source.contains("client.setScreen(null)"));
    }
}
