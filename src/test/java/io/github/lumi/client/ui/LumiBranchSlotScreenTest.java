package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiBranchSlotScreenTest {
    @Test
    void capturesOneArbitraryKeyForTheAltChord() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiBranchSlotScreen.java"));

        assertTrue(source.contains("public boolean keyPressed(KeyEvent event)"));
        assertTrue(source.contains(
                "slots.assignKey(snapshot, branch.name(), event.key())"));
        assertTrue(source.contains("luma.action.clear_bind"));
    }
}
