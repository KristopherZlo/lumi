package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiRecoveryScreenTest {
    @Test
    void exposesOnlyTheTwoSafeInterruptedApplyDirections() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiRecoveryScreen.java"));

        assertTrue(source.contains("luma.action.recovery_restore"));
        assertTrue(source.contains("luma.action.return_before_restore"));
        assertTrue(source.contains("shouldCloseOnEsc() { return false; }"));
        assertFalse(source.contains("recovery_save"));
        assertFalse(source.contains("recovery_discard"));
        assertFalse(source.contains("open_workspace"));
    }
}
