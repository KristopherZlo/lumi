package io.github.luma.ui.screen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectScreenHistoryBranchSelectionTest {

    @Test
    void historyBranchSelectionDoesNotSwitchTheActiveBranch() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/ProjectScreen.java"));
        int selectIndex = source.indexOf("        public void selectVariant(String variantId) {");
        int toggleIndex = source.indexOf("        public void toggleAllSaves() {", selectIndex);

        assertTrue(selectIndex >= 0, "ProjectScreen should keep a History branch selection action");
        assertTrue(toggleIndex > selectIndex, "The branch selection action should be bounded by the next action");

        String methodBody = source.substring(selectIndex, toggleIndex);

        assertFalse(
                methodBody.contains("switchVariant("),
                "Build History branch buttons should only choose which saves are displayed"
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
}
