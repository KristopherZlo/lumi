package io.github.luma;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LumaClientShortcutTest {

    @Test
    void pauseScreenSuppressesAndDrainsLumiShortcuts() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));

        assertTrue(source.contains("import net.minecraft.client.gui.screens.PauseScreen;"));
        assertTrue(source.contains("client.screen instanceof PauseScreen"));

        assertTrue(source.replace("\r\n", "\n").contains(
                "if (shortcutsSuppressed) {\n            this.drainLumiShortcutClicks();"
        ));
    }

    @Test
    void holdingActionKeyAlonePreparesRecentOverlayButNotPendingScan() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));

        assertTrue(source.replace("\r\n", "\n").contains(
                "overlayHold && !undoRedoKeys.previewActive()\n"
                        + "                ? RecentChangesOverlayCoordinator.PreviewTarget.BOTH"
        ));
        assertTrue(source.replace("\r\n", "\n").contains(
                "worldInputActive && overlayHold,\n"
                        + "                recentPreviewTarget"
        ));
        assertTrue(source.replace("\r\n", "\n").contains(
                "worldInputActive && overlayHold && undoRedoKeys.previewActive() && !recentPreviewActive"
        ));
    }
}
