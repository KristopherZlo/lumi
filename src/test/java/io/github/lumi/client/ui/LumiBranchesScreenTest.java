package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiBranchesScreenTest {
    @Test
    void keepsNamesClearOfActionsAndStacksCardsWhenNarrow() {
        int referenceWidth = LegacyWorkspaceLayout.fit(640, 360).contentWidth();
        int smallWidth = LegacyWorkspaceLayout.fit(427, 240).contentWidth();

        assertFalse(LumiBranchesScreen.stacksActions(referenceWidth));
        assertEquals(referenceWidth - 302,
                LumiBranchesScreen.inlineBranchNameWidth(referenceWidth));
        assertTrue(24 + LumiBranchesScreen.branchNameWidth(referenceWidth) + 6
                <= referenceWidth - 20 - 252);
        assertTrue(LumiBranchesScreen.stacksActions(smallWidth));
        assertTrue(LumiBranchesScreen.branchNameWidth(smallWidth) > 0);
        assertEquals(50, LumiBranchesScreen.rowStride(smallWidth));
        assertTrue(LumiBranchesScreen.visibleRows(220, 0, smallWidth) > 0);
    }

    @Test
    void exposesLegacyCreateMergeSwitchAndConfirmedDeleteActions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiBranchesScreen.java"));

        assertTrue(source.contains("luma.action.variant_create"));
        assertTrue(source.contains("luma.action.merge_into_current"));
        assertTrue(source.contains("luma.action.variant_switch"));
        assertTrue(source.contains("visibleRows()"));
        assertTrue(source.contains("addLegacyIconButton"));
        assertTrue(source.contains("name = addLegacyTextField("));
        assertTrue(source.contains("create.submit(name.getValue())"));
        assertTrue(source.contains("luma.action.delete_branch"));
        assertTrue(source.contains("deleter.accept(pendingDelete.name())"));
        assertTrue(source.contains("addDeleteConfirmation"));
        assertTrue(source.contains("ClientContextualHelpHint.BRANCHES"));
        assertTrue(source.contains("contentOffset"));
        assertTrue(source.contains("bindingLabel.apply(branch)"));
        assertTrue(source.contains("bindSlot.accept(branch)"));
        assertTrue(source.contains("\"rollback\""));
        assertTrue(source.contains("\"folder\""));
        assertTrue(source.contains("\"trash\""));
        assertTrue(source.contains("\"merge\""));
        assertTrue(source.contains("mergeButton.active = !branch.active()"));
        assertTrue(source.contains("public boolean mouseScrolled("));
        assertTrue(source.contains("addBranchActions(branch, rowY)"));
        assertTrue(source.contains("luma.screen.zone_ideas.title"));
        assertTrue(source.contains("luma.ideas.zone_badge"));
    }
}
