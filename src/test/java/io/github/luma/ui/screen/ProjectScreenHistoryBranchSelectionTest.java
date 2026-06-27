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
        int restoreIndex = source.indexOf("        public void requestRestore(ProjectVariant variant, ProjectVersion version) {", toggleIndex);

        assertTrue(toggleIndex >= 0, "ProjectScreen should keep a History cards/graph toggle action");
        assertTrue(restoreIndex > toggleIndex, "The History view toggle action should be bounded by the next action");

        String methodBody = source.substring(toggleIndex, restoreIndex);

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
}
