package io.github.luma.debug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumaDiagnosticsLogTest {

    @Test
    void fluidUndoLogIsExplicitAndSeparateFromLoadLog() {
        String previousLoadLog = System.getProperty("lumi.loadLog");
        String previousFluidLog = System.getProperty("lumi.fluidUndoLog");
        try {
            System.setProperty("lumi.loadLog", "true");
            System.clearProperty("lumi.fluidUndoLog");

            assertFalse(LumaDiagnosticsLog.fluidUndoEnabled());

            System.setProperty("lumi.fluidUndoLog", "true");

            assertTrue(LumaDiagnosticsLog.fluidUndoEnabled());
            assertTrue(LumaDiagnosticsLog.fluidUndoPath().endsWith(Path.of("logs", "lumi-fluid-undo.log")));
        } finally {
            restoreProperty("lumi.loadLog", previousLoadLog);
            restoreProperty("lumi.fluidUndoLog", previousFluidLog);
            LumaDiagnosticsLog.close();
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
            return;
        }
        System.setProperty(name, value);
    }
}
