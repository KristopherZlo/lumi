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
    }
}
