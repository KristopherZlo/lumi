package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScreenHistoryBranchSelectionTest {

    @Test
    void historyGraphToggleDoesNotSwitchTheActiveBranch() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/ProjectScreen.java"));
        int toggleIndex = source.indexOf("        public void setHistoryGraphVisible(boolean visible) {");
        int selectIndex = source.indexOf("        public void selectHistoryVariant(String variantId) {", toggleIndex);

        assertTrue(toggleIndex >= 0, "ProjectScreen should keep a History cards/graph toggle action");
        assertTrue(selectIndex > toggleIndex, "The History view toggle action should be bounded by the next action");

        String methodBody = source.substring(toggleIndex, selectIndex);

        assertTrue(
                methodBody.contains("historyGraphVisible = visible"),
                "Build History view toggle should only switch the local history presentation"
        );
        assertFalse(
                methodBody.contains("switchVariant("),
                "Build History view toggle should not switch the active branch"
        );
    }

    @Test
    void historyKeepsBranchViewButtonsAndLocalSelection() throws IOException {
        String sections = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectScreenSections.java"));
        String screen = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/ProjectScreen.java"));

        assertTrue(
                sections.contains("section.child(this.historyBranchStrip(model));"),
                "Build History should render branch view buttons independent of active branch switching"
        );
        assertTrue(
                sections.contains("this.actions.selectHistoryVariant(variant.id())"),
                "Branch view buttons should update the local selected history branch"
        );
        assertTrue(
                screen.contains("selectedVariantId = variantId == null ? \"\" : variantId"),
                "ProjectScreen should store the local selected history branch"
        );
    }

    @Test
    void historyGraphUsesTheSameSelectedBranchProjectionAsCards() throws IOException {
        String sections = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectScreenSections.java"));

        assertTrue(
                sections.contains("this.graphView(model, selectedVariant, entries)"),
                "Build History graph should use the same selected-branch entries as cards"
        );
    }

    @Test
    void selectedHistoryBranchFollowsActiveBranchSwitchWhenItWasShowingTheOldActiveBranch() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/ProjectScreen.java"));

        assertTrue(source.contains("lastActiveVariantId"));
        assertTrue(
                source.contains("this.selectedVariantId.equals(this.lastActiveVariantId)"),
                "ProjectScreen should switch the viewed history branch when the active branch changes"
        );
    }

    @Test
    void historyBranchCreationCreatesAndSwitchesBranch() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/ProjectScreen.java"));
        int methodIndex = source.indexOf("    private void createBranch(BranchCreationDialogState dialog) {");
        int nextMethodIndex = source.indexOf("    private void closeBranchDialog() {", methodIndex);

        assertTrue(methodIndex >= 0, "ProjectScreen should keep a branch creation action");
        assertTrue(nextMethodIndex > methodIndex, "The branch creation action should be bounded by the next method");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(
                methodBody.contains("createAndSwitchVariant("),
                "Build History branch-from-save should create the branch and switch to it"
        );
        assertFalse(
                methodBody.contains("createVariant("),
                "Build History branch-from-save should not use metadata-only branch creation"
        );
    }

    @Test
    void saveDetailsBranchCreationCreatesAndSwitchesBranch() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/SaveDetailsScreen.java"));
        int methodIndex = source.indexOf("    private void createBranch(BranchCreationDialogState dialog) {");
        int nextMethodIndex = source.indexOf("    private void closeBranchDialog() {", methodIndex);

        assertTrue(methodIndex >= 0, "SaveDetailsScreen should keep a branch creation action");
        assertTrue(nextMethodIndex > methodIndex, "The branch creation action should be bounded by the next method");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(
                methodBody.contains("createAndSwitchVariant("),
                "Save details branch-from-save should create the branch and switch to it"
        );
        assertFalse(
                methodBody.contains("createVariant("),
                "Save details branch-from-save should not use metadata-only branch creation"
        );
    }

    @Test
    void historyRestoreUsesTheSelectedBranchTarget() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/ProjectScreen.java"));
        int methodIndex = source.indexOf("    private void executeRestore(ProjectVariant variant, ProjectVersion version) {");
        int nextMethodIndex = source.indexOf("    private void executeSelectedRestore(ProjectVersion version, PartialRestoreMode mode, Bounds3i bounds) {", methodIndex);

        assertTrue(methodIndex >= 0, "ProjectScreen should keep a restore execution action");
        assertTrue(nextMethodIndex > methodIndex, "The restore execution action should be bounded by the next method");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(
                methodBody.contains("restoreVersion(this.projectName, version.id(), variant.id())"),
                "Build History restore should preserve the branch whose history card started the restore"
        );
    }

    @Test
    void onboardingHistorySpotlightTargetsLatestRestoreButton() throws IOException {
        String sections = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectScreenSections.java"));
        String card = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectSaveCardView.java"));

        assertTrue(sections.contains("onboardingLatestRestoreButton"));
        assertTrue(sections.contains("this.saveCardView.onboardingRestoreButton()"));
        assertTrue(card.contains("onboardingRestoreButton"));
    }

    @Test
    void partialRestoreConfirmationsUseScopedEntitySummaries() throws IOException {
        String projectScreen = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/ProjectScreen.java"));
        String saveDetails = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/SaveDetailsScreen.java"));

        assertTrue(projectScreen.contains("this.actionController::restoreEntityTypes"));
        assertTrue(projectScreen.contains("pendingRestoreMode"));
        assertTrue(saveDetails.contains("this.controller.restoreEntityTypes(partialRequest)"));
        assertTrue(saveDetails.contains("pendingPartialRestoreRequest"));
    }

    @Test
    void saveDetailsPartialRestoreApplyOpensRestoreConfirmation() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/SaveDetailsScreen.java"));
        int methodIndex = source.indexOf("        public void apply(PartialRestoreRequest request) {");
        int nextMethodIndex = source.indexOf("        @Override", methodIndex + 1);

        assertTrue(methodIndex >= 0, "Save details should keep the partial restore apply action");
        assertTrue(nextMethodIndex > methodIndex, "The partial restore apply action should be bounded by the next override");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(methodBody.contains("pendingPartialRestoreRequest = request"));
        assertTrue(methodBody.contains("pendingRestoreConfirmation = true"));
        assertFalse(
                methodBody.contains("controller.partialRestore(request)"),
                "Partial restore apply must go through restore confirmation so entity types can be excluded"
        );
    }

    @Test
    void restoreEntityTypeSelectionUsesCheckboxComponent() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/RestoreConfirmationDialogView.java"));

        assertTrue(source.contains("UIComponents.checkbox"));
        assertFalse(source.contains("\"[x] \""));
        assertFalse(source.contains("\"[ ] \""));
    }

    @Test
    void restoreConfirmationDoesNotReplayModalOpenAnimationOnCheckboxToggle() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/RestoreConfirmationDialogView.java"));
        String ui = Files.readString(Path.of("src/client/java/io/github/luma/ui/LumaUi.java"));

        assertTrue(
                source.contains("LumaUi.modalFrame(Math.max(280, Math.min(420, model.width() - 24)), false)"),
                "Restore confirmation rebuilds while checkbox state changes, so its frame must not restart open animation"
        );
        assertTrue(
                ui.contains("modalFrame(int width, boolean animate)"),
                "LumaUi should expose the existing modal frame without forcing animation"
        );
    }
}
