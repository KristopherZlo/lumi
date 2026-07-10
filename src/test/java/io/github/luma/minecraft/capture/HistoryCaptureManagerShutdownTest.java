package io.github.luma.minecraft.capture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HistoryCaptureManagerShutdownTest {

    @Test
    void shutdownUsesTheFinalReconciliationDrain() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/luma/minecraft/capture/HistoryCaptureManager.java"
        ));
        String flushAll = source.substring(
                source.indexOf("public void flushAll"),
                source.indexOf("public void drainUndoRedoStabilization")
        );

        assertTrue(flushAll.contains("this.reconcileSession(server, trackedProject, sessionState, true);"));
        assertFalse(flushAll.contains("this.reconcileSession(server, trackedProject, sessionState, false);"));
    }
}
