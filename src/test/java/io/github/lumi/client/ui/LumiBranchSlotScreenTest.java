package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        assertTrue(source.contains("Consumer<String> feedback"));
        assertTrue(source.contains("feedback.accept("));
        assertFalse(source.contains("displayClientMessage("));
        assertTrue(source.contains("luma.action.clear_bind"));
    }

    @Test
    void clearActionFitsTheMinimumViewport() {
        for (int[] viewport : new int[][] {{640, 360}, {427, 240}, {320, 180}}) {
            LumiModalLayout layout = LumiBranchSlotScreen.fitPanel(
                    viewport[0], viewport[1]);
            assertTrue(layout.x() + layout.width() <= viewport[0]);
            assertTrue(layout.y() + layout.height() <= viewport[1]);
            assertTrue(LumiBranchSlotScreen.actionOffset(layout.height()) + 20
                    <= layout.height());
        }
        assertEquals(164, LumiBranchSlotScreen.fitPanel(320, 180).height());
    }
}
