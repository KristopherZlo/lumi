package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDeleteBranchScreenTest {
    @Test
    void requiresTheVisibleBranchNameBeforeDeleting() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDeleteBranchScreen.java"));

        assertTrue(source.contains("luma.ideas.delete_confirm_help"));
        assertTrue(source.contains("shortName(branch.name()).equals("));
        assertTrue(source.contains("submit.active ="));
        assertTrue(source.contains("delete.accept(branch.name())"));
    }
}
