package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiPendingChangeOverlayTest {
    @Test
    void xrayUsesTheRemappableActionBinding() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiPendingChangeOverlay.java"));

        assertTrue(source.contains(
                "LumiHotkeys.actionModifierDown(client.options.keyMappings)"));
        assertFalse(source.contains("KEY_LALT"));
        assertFalse(source.contains("KEY_RALT"));
    }
}
