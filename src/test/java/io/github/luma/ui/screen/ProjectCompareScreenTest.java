package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void comparePageKeepsFixedControlsAroundIndependentScrollableHistories() throws IOException {
        String screen = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/ProjectCompareScreen.java"),
                StandardCharsets.UTF_8
        );
        String sections = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectCompareScreenSections.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(screen.contains("bodyScroll"), "Compare page should not wrap the whole body in one scroll");
        assertTrue(sections.contains("LumaUi.screenScroll(Sizing.fill(100), Sizing.expand(100)"));
        assertTrue(sections.contains("LumaUi.panel(Sizing.fill(100), Sizing.expand(100))"));
        assertTrue(sections.contains("ProjectUiSupport.versionPreview"));
        assertFalse(sections.contains("columns.verticalAlignment(VerticalAlignment.CENTER)"));
    }

    @Test
    void comparePageExposesVisibilityToggleAndHalfSizedCenterIcon() throws IOException {
        String sections = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectCompareScreenSections.java"),
                StandardCharsets.UTF_8
        );
        String screen = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/ProjectCompareScreen.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(sections.contains("ButtonComponent visibility = LumaUi.iconButton("));
        assertTrue(sections.contains("this.overlayIcon(model)"));
        assertTrue(sections.contains("CompareOverlayRenderer.visibleFor"));
        assertTrue(sections.contains("UIComponents.texture(COMPARE_ICON, 0, 0, 24, 24, 24, 24)"));
        assertTrue(sections.contains("icon.sizing(Sizing.fixed(12), Sizing.fixed(12));"));
        assertTrue(sections.contains("visibility.active(this.canCompare(model) || this.overlayMatches(model));"));
        assertTrue(screen.contains("CompareOverlayRenderer.hasDataFor"));
        assertTrue(screen.contains("closeAfterPendingCompareOverlay"));
        assertTrue(screen.contains("toggleOverlayVisibility"));
    }

    @Test
    void selectingSavePreservesThatColumnScrollPosition() throws IOException {
        String sections = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectCompareScreenSections.java"),
                StandardCharsets.UTF_8
        );
        String screen = Files.readString(
                Path.of("src/client/java/io/github/luma/ui/screen/ProjectCompareScreen.java"),
                StandardCharsets.UTF_8
        );

        assertTrue(sections.contains("registerHistoryScroll(side, scroll)"));
        assertTrue(screen.contains("private LumaScrollContainer<FlowLayout> leftHistoryScroll"));
        assertTrue(screen.contains("private void rebuild(Side preserveSide)"));
        assertTrue(screen.contains("this.rebuild(side)"));
        assertTrue(screen.contains("this.historyScroll(preserveSide)"));
    }
}
