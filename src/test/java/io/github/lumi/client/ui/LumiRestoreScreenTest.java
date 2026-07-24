package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiRestoreScreenTest {
    @Test
    void oneModalOwnsWholeAndPreviewGatedPartialRestore() throws Exception {
        String restore = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiRestoreScreen.java"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));
        String networking = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClientNetworking.java"));

        assertTrue(restore.contains("RestoreMode.WHOLE"));
        assertTrue(restore.contains("RestoreMode.SELECTED"));
        assertTrue(restore.contains("RestoreMode.OUTSIDE"));
        assertTrue(restore.contains("accept(PartialRestorePlanPayload"));
        assertTrue(restore.contains("accepted.accept(requestId)"));
        assertTrue(client.contains("LumiSelectionTool.held(Minecraft.getInstance())"));
        assertTrue(client.contains("? SELECTION.bounds() : Optional.empty()"));
        assertFalse(client.contains("LumiPartialRestoreScreen"));
        assertTrue(networking.contains("Kind.RESTORE_AREA_PLAN"));
        assertTrue(networking.contains("Kind.RESTORE_AREA_APPLY"));
    }

    @Test
    void compactLayoutKeepsActionsInsideThePanel() {
        for (int height : new int[] {164, 184, 240}) {
            assertTrue(LumiRestoreScreen.modeOffset(height) + 20
                    <= LumiRestoreScreen.actionOffset(height));
            assertTrue(LumiRestoreScreen.actionOffset(height) + 20
                    <= LumiRestoreScreen.statusOffset(height));
            assertTrue(LumiRestoreScreen.statusOffset(height) + 9
                    <= LumiRestoreScreen.cancelOffset(height));
            assertTrue(LumiRestoreScreen.cancelOffset(height) + 20 <= height);
        }
    }
}
