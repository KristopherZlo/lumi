package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiResponsiveModalLayoutTest {
    @Test
    void saveAndPackageInspectionKeepFieldsAndActionsInsideNarrowViewports() {
        for (int[] viewport : new int[][] {{427, 240}, {320, 200}}) {
            LegacyModalLayout save = LumiSaveScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertInside(viewport, save);
            assertTrue(LumiSaveScreen.tagsBottom(save.height())
                    <= LumiSaveScreen.actionOffset(save.height()));

            LegacyModalLayout inspection = LumiPackageInspectionScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertInside(viewport, inspection);
            assertTrue(LumiPackageInspectionScreen.actionOffset(
                    inspection.height()) + 18 <= inspection.height());
        }
    }

    @Test
    void mergeReflowsRowsAndPreviewsAboveItsFooter() {
        for (int[] viewport : new int[][] {{427, 240}, {320, 200}}) {
            LegacyModalLayout layout = LumiMergeScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertInside(viewport, layout);
            assertTrue(LumiMergeScreen.actionOffset(layout.height()) + 18
                    <= layout.height());
            assertTrue(LumiMergeScreen.visibleBranchRows(layout.height()) > 0);
            assertTrue(LumiMergeScreen.previewWidth(layout.width()) * 2 + 14
                    <= layout.width());
            assertTrue(LumiMergeScreen.previewHeight(layout.height()) > 0);
        }
    }

    private static void assertInside(int[] viewport, LegacyModalLayout layout) {
        assertTrue(layout.x() >= 0 && layout.y() >= 0);
        assertTrue(layout.x() + layout.width() <= viewport[0]);
        assertTrue(layout.y() + layout.height() <= viewport[1]);
    }
}
