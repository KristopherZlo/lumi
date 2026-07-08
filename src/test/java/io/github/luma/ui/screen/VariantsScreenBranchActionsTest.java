package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantsScreenBranchActionsTest {

    private final String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/VariantsScreen.java"));

    VariantsScreenBranchActionsTest() throws IOException {
    }

    @Test
    void branchDeleteConfirmationUsesModalInsteadOfExpandedCardContent() {
        assertTrue(this.source.contains("branchDeleteDialogOverlay()"));
        assertFalse(this.source.contains("card.child(this.deleteConfirmation(variant))"));
    }

    @Test
    void branchCardsExposeBindActionAndDialog() {
        assertTrue(this.source.contains("luma.action.bind_branch"));
        assertTrue(this.source.contains("branchBindDialogOverlay()"));
        assertTrue(this.source.contains("setVariantSwitchKey"));
        assertTrue(this.source.contains("LumaUi.keybindChip"));
    }

    @Test
    void branchCardsShowBindInTopRightAndUseActiveBorderInsteadOfCurrentBadge() {
        String methodBody = methodBody(
                this.source,
                "    private FlowLayout variantCard(ProjectVariant variant) {",
                "    private VariantsViewState loadState() {"
        );

        assertTrue(methodBody.contains("LumaUi.activeInsetPanel"));
        assertTrue(methodBody.contains("branchCardTitleRow(variant)"));
        assertFalse(methodBody.contains("luma.idea.current_badge"));
    }

    @Test
    void branchBindChipUsesConfiguredActionKeySprite() {
        assertTrue(this.source.contains("LumiClientKeyBindings.Role.ACTION"));
        assertFalse(this.source.contains("key.keyboard.left.alt"));
    }

    @Test
    void mergeActionOpensConfirmationModal() {
        String methodBody = methodBody(
                this.source,
                "    private FlowLayout variantCard(ProjectVariant variant) {",
                "    private VariantsViewState loadState() {"
        );

        assertTrue(methodBody.contains("openBranchMergeDialog(variant.id())"));
        assertFalse(methodBody.contains("mergeVariantIntoCurrent(this.projectName, variant.id())"));
        assertTrue(this.source.contains("branchMergeDialogOverlay()"));
    }

    @Test
    void mergeConfirmationShowsBothHeadPreviewsAndRequiresSourceBranchName() {
        String methodBody = methodBody(
                this.source,
                "    private FlowLayout branchMergeDialogOverlay() {",
                "    private FlowLayout branchBindDialogOverlay() {"
        );

        assertTrue(methodBody.contains("headVersion(sourceVariant)"));
        assertTrue(methodBody.contains("ProjectUiSupport.activeHead"));
        assertTrue(methodBody.contains("ProjectUiSupport.versionPreview"));
        assertTrue(methodBody.contains("MERGE_CHEVRON"));
        assertTrue(methodBody.contains("input.setHint(Component.literal(ProjectUiSupport.displayVariantName(sourceVariant)))"));
        assertTrue(methodBody.contains("merge.active(this.canConfirmMerge(sourceVariant))"));
    }

    @Test
    void mergeConfirmationUsesFixedPreviewBoxesAndFallbackPlaceholder() {
        String methodBody = methodBody(
                this.source,
                "    private FlowLayout mergePreviewColumn(Component label, ProjectVersion version) {",
                "    private FlowLayout branchBindDialogOverlay() {"
        );

        assertTrue(this.source.contains("MERGE_PREVIEW_WIDTH"));
        assertTrue(this.source.contains("MERGE_PREVIEW_HEIGHT"));
        assertTrue(methodBody.contains("Sizing.fixed(MERGE_PREVIEW_WIDTH)"));
        assertTrue(methodBody.contains("ProjectUiSupport.versionPreview"));
        assertTrue(methodBody.contains("MERGE_PREVIEW_HEIGHT"));
        assertFalse(methodBody.contains("128, 72, 88"));
    }

    private static String methodBody(String source, String start, String end) {
        int methodIndex = source.indexOf(start);
        int nextMethodIndex = source.indexOf(end, methodIndex);

        assertTrue(methodIndex >= 0, "VariantsScreen should keep " + start.trim());
        assertTrue(nextMethodIndex > methodIndex, "The method should be bounded by " + end.trim());

        return source.substring(methodIndex, nextMethodIndex);
    }
}
