package io.github.lumi.minecraft.world;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RestoreCrashInjectionTest {
    @TempDir Path temporary;

    @Test
    void unmatchedCutpointDoesNothing() {
        Path marker = temporary.resolve("marker");
        System.setProperty(RestoreCrashInjection.PHASE_PROPERTY, "force");
        System.setProperty(RestoreCrashInjection.MARKER_PROPERTY, marker.toString());
        try {
            RestoreCrashInjection.hit(RestoreCrashInjection.Cutpoint.WRITE);
            assertFalse(Files.exists(marker));
        } finally {
            System.clearProperty(RestoreCrashInjection.PHASE_PROPERTY);
            System.clearProperty(RestoreCrashInjection.MARKER_PROPERTY);
        }
    }
}
