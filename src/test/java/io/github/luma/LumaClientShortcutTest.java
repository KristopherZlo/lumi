package io.github.luma;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void holdingActionKeyPreparesTheDurablePendingOverlay() throws IOException {
        String source = Files.readString(Path.of("src/client/java/io/github/luma/LumaClient.java"));

        assertTrue(source.replace("\r\n", "\n").contains(
                "PendingChangesOverlayCoordinator.getInstance().tick(\n"
                        + "                client,\n"
                        + "                worldInputActive && overlayHold"
        ));
        assertFalse(source.contains("RecentChangesOverlay"));
    }
}
