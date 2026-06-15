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
}
