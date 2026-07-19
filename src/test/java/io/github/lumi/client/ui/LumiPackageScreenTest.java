package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiPackageScreenTest {
    @Test
    void keepsPackageBandsSeparatedAtSupportedViewports() {
        int[][] viewports = {{320, 180}, {427, 240}, {640, 360}};
        int[] rowsWithoutHint = {1, 3, 5};
        int[] rowsWithHint = {0, 0, 2};
        for (int index = 0; index < viewports.length; index++) {
            LegacyWorkspaceLayout layout = LegacyWorkspaceLayout.fit(
                    viewports[index][0], viewports[index][1]);
            var plain = LumiPackageScreen.packageGeometry(
                    layout.windowHeight(), 0);
            var hinted = LumiPackageScreen.packageGeometry(
                    layout.windowHeight(), 70);

            assertBandsInside(plain, layout.windowHeight());
            assertEquals(rowsWithoutHint[index],
                    LumiPackageScreen.visibleRows(plain));
            assertEquals(rowsWithHint[index],
                    LumiPackageScreen.visibleRows(hinted));
            assertTrue(hinted.deleteY() + hinted.deleteHeight()
                    <= hinted.listBottom());
            assertTrue(hinted.deleteActionY() + 18
                    <= hinted.listBottom());
        }
        assertFalse(LumiPackageScreen.packageGeometry(
                LegacyWorkspaceLayout.fit(320, 180).windowHeight(), 70)
                .contentVisible());
    }

    @Test
    void reservesTheRealImportedAndLocalActionBounds() {
        for (int[] viewport : new int[][] {{320, 180}, {427, 240}, {640, 360}}) {
            int panelWidth = LegacyWorkspaceLayout.fit(
                    viewport[0], viewport[1]).contentWidth();
            int importedWidth = LumiPackageScreen.rowTextWidth(panelWidth, true);
            int localWidth = LumiPackageScreen.rowTextWidth(panelWidth, false);

            assertEquals(panelWidth - 128, importedWidth);
            assertEquals(panelWidth - 130, localWidth);
            assertTrue(24 + importedWidth + 6 <= panelWidth - 98);
            assertTrue(24 + localWidth + 6 <= panelWidth - 100);
        }
    }

    @Test
    void exposesLegacyLocalPackageControls() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiPackageScreen.java"));

        assertTrue(source.contains("luma.action.open_packages_folder"));
        assertTrue(source.contains("luma.share.package_files_empty"));
        assertTrue(source.contains("browser.local(index).name().value()"));
        assertTrue(source.contains("PackageScreenController.Action.INSPECT"));
        assertTrue(source.contains("luma.action.combine_with_build"));
        assertTrue(source.contains("luma.action.delete_package"));
        assertTrue(source.contains("\"join\""));
        assertTrue(source.contains("\"branch\""));
        assertTrue(source.contains("\"trash\""));
        assertTrue(source.contains("ClientContextualHelpHint.IMPORT_EXPORT"));
        assertTrue(source.contains("moveContextualHint"));
        assertTrue(source.contains("luma.share.include_previews"));
        assertTrue(source.contains("includePreview"));
    }

    private static void assertBandsInside(
            LumiPackageScreen.PackageGeometry geometry, int panelHeight) {
        assertTrue(geometry.contentVisible());
        assertTrue(geometry.fieldY() + 20 <= geometry.actionY());
        assertTrue(geometry.actionY() + 18 <= geometry.optionY());
        assertTrue(geometry.optionY() + 18 <= geometry.tabsY());
        assertTrue(geometry.tabsY() + 18 <= geometry.listY());
        assertEquals(geometry.statusY() - 4, geometry.listBottom());
        assertTrue(geometry.statusY() + 9 <= panelHeight);
    }
}
