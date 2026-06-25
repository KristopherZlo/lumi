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
    void historyViewToggleUsesRightAlignedIconButtons() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/section/ProjectScreenSections.java"));
        int methodIndex = source.indexOf("    private FlowLayout historyToolbar(Model model) {");
        int nextMethodIndex = source.indexOf("    private FlowLayout tagFilter(Model model) {", methodIndex);

        assertTrue(methodIndex >= 0, "Project history toolbar should exist");
        assertTrue(nextMethodIndex > methodIndex, "Project history toolbar should be bounded by tagFilter");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(methodBody.contains("row.child(this.tagFilter(model));"), "Tag filter should stay on the left");
        assertTrue(methodBody.contains("UIContainers.verticalFlow(Sizing.expand(100), Sizing.fixed(1))"),
                "History toolbar should push view toggles to the right");
        assertTrue(methodBody.contains("LumaUi.iconButton(") && methodBody.contains("\"folder-open\""),
                "Cards view toggle should be an icon button");
        assertTrue(methodBody.contains("LumaUi.iconButton(") && methodBody.contains("\"git-branch\""),
                "Graph view toggle should be an icon button");
        assertFalse(methodBody.contains("LumaUi.button(Component.translatable(\"luma.history.view_cards\")"),
                "Cards view toggle should not be a text button");
        assertFalse(methodBody.contains("LumaUi.button(Component.translatable(\"luma.history.view_graph\")"),
                "Graph view toggle should not be a text button");
    }

    @Test
    void workZoneHistoryViewToggleUsesRightAlignedIconButtons() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/WorkZoneScreen.java"));
        int methodIndex = source.indexOf("    private FlowLayout zoneHistoryToolbar() {");
        int nextMethodIndex = source.indexOf("    private FlowLayout zoneTagFilter() {", methodIndex);

        assertTrue(methodIndex >= 0, "Work-zone history toolbar should exist");
        assertTrue(nextMethodIndex > methodIndex, "Work-zone history toolbar should be bounded by zoneTagFilter");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(methodBody.contains("row.child(this.zoneTagFilter());"), "Zone tag filter should stay on the left");
        assertTrue(methodBody.contains("UIContainers.verticalFlow(Sizing.expand(100), Sizing.fixed(1))"),
                "Zone history toolbar should push view toggles to the right");
        assertTrue(methodBody.contains("LumaUi.iconButton(\"folder-open\""),
                "Zone cards view toggle should be an icon button");
        assertTrue(methodBody.contains("LumaUi.iconButton(\"git-branch\""),
                "Zone graph view toggle should be an icon button");
        assertFalse(methodBody.contains("LumaUi.button(Component.translatable(\"luma.history.view_cards\")"),
                "Zone cards view toggle should not be a text button");
        assertFalse(methodBody.contains("LumaUi.button(Component.translatable(\"luma.history.view_graph\")"),
                "Zone graph view toggle should not be a text button");
    }

    @Test
    void saveDetailsPreviewPanUsesCustomIconButtons() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/ui/screen/SaveDetailsScreen.java"));
        int methodIndex = source.indexOf("    private FlowLayout previewPanel(ProjectVersion version) {");
        int nextMethodIndex = source.indexOf("    private void panPreview(int deltaX, int deltaY) {", methodIndex);

        assertTrue(methodIndex >= 0, "Save details preview panel should exist");
        assertTrue(nextMethodIndex > methodIndex, "Save details preview panel should be bounded by panPreview");

        String methodBody = source.substring(methodIndex, nextMethodIndex);

        assertTrue(methodBody.contains("LumaUi.iconButton(\"arrow-left\""),
                "Preview pan-left should use a custom icon");
        assertTrue(methodBody.contains("LumaUi.iconButton(\"arrow-right\""),
                "Preview pan-right should use a custom icon");
        assertFalse(methodBody.contains("Component.literal(\"<\")"),
                "Preview pan-left should not use a text arrow");
        assertFalse(methodBody.contains("Component.literal(\">\")"),
                "Preview pan-right should not use a text arrow");
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
}
