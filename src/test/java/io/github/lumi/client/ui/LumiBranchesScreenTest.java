package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiBranchesScreenTest {
    @Test
    void exposesLegacyCreateMergeSwitchAndConfirmedDeleteActions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiBranchesScreen.java"));

        assertTrue(source.contains("luma.action.variant_create"));
        assertTrue(source.contains("luma.action.merge_into_current"));
        assertTrue(source.contains("luma.action.variant_switch"));
        assertTrue(source.contains("visibleRows()"));
        assertTrue(source.contains("addLegacyIconButton"));
        assertTrue(source.contains("if (!branch.active())"));
        assertTrue(source.contains("luma.action.delete_branch"));
        assertTrue(source.contains("deleter.accept(pendingDelete.name())"));
        assertTrue(source.contains("addDeleteConfirmation"));
        assertTrue(source.contains("ClientContextualHelpHint.BRANCHES"));
        assertTrue(source.contains("contentOffset"));
        assertTrue(source.contains("luma.action.bind_branch"));
        assertTrue(source.contains("bindSlot.accept(branch)"));
        assertTrue(source.contains("luma.screen.zone_ideas.title"));
        assertTrue(source.contains("luma.ideas.zone_badge"));
    }
}
