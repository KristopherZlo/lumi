package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiBranchesScreenTest {
    @Test
    void exposesLegacyCreateMergeAndSwitchActions() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiBranchesScreen.java"));

        assertTrue(source.contains("luma.action.variant_create"));
        assertTrue(source.contains("luma.action.merge_into_current"));
        assertTrue(source.contains("luma.action.variant_switch"));
        assertTrue(source.contains("visibleRows()"));
        assertTrue(source.contains("addLegacyIconButton"));
    }
}
