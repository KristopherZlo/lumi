package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LumiResponsiveModalLayoutTest {
    @Test
    void saveAndPackageInspectionKeepFieldsAndActionsInsideNarrowViewports() {
        for (int[] viewport : new int[][] {
                {427, 240}, {320, 200}, {320, 180}}) {
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
        for (int[] viewport : new int[][] {
                {640, 360}, {427, 240}, {320, 200}, {320, 180}}) {
            LegacyModalLayout layout = LumiMergeScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertInside(viewport, layout);
            assertTrue(LumiMergeScreen.actionOffset(layout.height()) + 18
                    <= layout.height());
            assertTrue(52 + 9 <= LumiMergeScreen.listOffset());
            assertTrue(LumiMergeScreen.visibleBranchRows(layout.height()) > 0);
            assertTrue(LumiMergeScreen.previewWidth(layout.width()) * 2 + 14
                    <= layout.width());
            assertTrue(LumiMergeScreen.previewHeight(layout.height()) > 0);
            assertTrue(LumiMergeScreen.confirmationTextOffset(
                    layout.height(), true) + 9
                    <= LumiMergeScreen.actionOffset(layout.height()) - 13);
        }
    }

    @Test
    void mergeSourceListCanScrollPastEveryVisibleCapacity() {
        for (int[] viewport : new int[][] {
                {320, 180, 3}, {427, 240, 6}, {640, 360, 6}}) {
            LegacyModalLayout layout = LumiMergeScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertEquals(viewport[2],
                    LumiMergeScreen.visibleBranchRows(layout.height()));
            assertEquals(2, LumiMergeScreen.maximumSourceScroll(
                    layout.height(), viewport[2] + 2));
        }
    }

    @Test
    void deleteZonePanelAndFooterStayInsideSupportedViewports() {
        for (int[] viewport : new int[][] {
                {320, 180}, {427, 240}, {640, 360}}) {
            LegacyModalLayout layout = LumiDeleteZoneScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertInside(viewport, layout);
            assertTrue(LumiDeleteZoneScreen.actionOffset(layout.height()) + 18
                    <= layout.height());
        }
        LegacyModalLayout minimum = LumiDeleteZoneScreen.fitPanel(320, 180);
        assertEquals(8, minimum.y());
        assertEquals(164, minimum.height());
    }

    @Test
    void tagEditorUsesOnlyTheHeightNeededByItsFieldAndActions() {
        for (int[] viewport : new int[][] {
                {320, 180}, {427, 240}, {640, 360}}) {
            LegacyModalLayout layout = LumiVersionTagsScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertInside(viewport, layout);
            assertEquals(124, layout.height());
        }
    }

    @Test
    void diagnosticsFitsItsHintAndRowsInsideNarrowPanels() {
        for (int[] viewport : new int[][] {
                {427, 240}, {320, 200}, {320, 180}}) {
            LegacyModalLayout layout = LumiDiagnosticsScreen.fitPanel(
                    viewport[0], viewport[1], 48);
            assertInside(viewport, layout);
            int rowAreaHeight = LumiDiagnosticsScreen.rowAreaHeight(
                    layout.height(), 48);
            int columns = LumiDiagnosticsScreen.rowColumns(rowAreaHeight - 10);
            int rowsPerColumn = (8 + columns - 1) / columns;
            int stride = LumiDiagnosticsScreen.rowStride(
                    rowAreaHeight - 10, rowsPerColumn);
            assertTrue(stride >= 9);
            assertTrue(stride * (rowsPerColumn - 1) + 9
                    <= rowAreaHeight - 10);
        }
    }

    @Test
    void versionDetailsReflowsPreviewAndActionsInsideNarrowPanels() {
        for (int[] viewport : new int[][] {
                {427, 240}, {320, 200}, {320, 180}}) {
            LegacyModalLayout layout = LumiVersionDetailsScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertInside(viewport, layout);
            int primaryActions = LumiVersionDetailsScreen.primaryActionOffset(
                    layout.width(), layout.height());
            int secondaryActions = LumiVersionDetailsScreen.secondaryActionOffset(
                    layout.width(), layout.height());
            int previewControls = LumiVersionDetailsScreen.previewControlsOffset(
                    layout.width(), layout.height());
            assertTrue(primaryActions + 18 <= layout.height());
            assertTrue(secondaryActions + 18 <= primaryActions);
            assertTrue(previewControls + 18 <= secondaryActions);
            assertTrue(LumiVersionDetailsScreen.previewOffset(
                    layout.width(), layout.height())
                    + LumiVersionDetailsScreen.previewHeight(
                            layout.width(), layout.height())
                    <= previewControls);
            assertTrue(LumiVersionDetailsScreen.previewWidth(
                    layout.width(), layout.height()) + 52
                    <= layout.width());
        }
    }

    private static void assertInside(int[] viewport, LegacyModalLayout layout) {
        assertTrue(layout.x() >= 0 && layout.y() >= 0);
        assertTrue(layout.x() + layout.width() <= viewport[0]);
        assertTrue(layout.y() + layout.height() <= viewport[1]);
    }
}
