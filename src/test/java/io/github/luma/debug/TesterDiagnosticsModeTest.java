package io.github.luma.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TesterDiagnosticsModeTest {

    private static final List<String> PROPERTIES = List.of(
            "lumi.testerDiagnostics",
            "lumi.loadLog",
            "lumi.clientLoadLog",
            "lumi.lightLog",
            "lumi.blockApplyLog",
            "lumi.partialRestoreLog",
            "lumi.loadLog.slowMs",
            "lumi.loadLog.summarySeconds",
            "lumi.loadLog.top",
            "lumi.clientLoadLog.sampleTicks",
            "lumi.clientLoadLog.gpuSampleSeconds"
    );

    private final Map<String, String> originalProperties = new HashMap<>();

    @BeforeEach
    void captureProperties() {
        this.originalProperties.clear();
        for (String property : PROPERTIES) {
            this.originalProperties.put(property, System.getProperty(property));
            System.clearProperty(property);
        }
    }

    @AfterEach
    void restoreProperties() {
        for (String property : PROPERTIES) {
            String original = this.originalProperties.get(property);
            if (original == null) {
                System.clearProperty(property);
            } else {
                System.setProperty(property, original);
            }
        }
    }

    @Test
    void testerVersionEnablesBoundedDiagnosticsByDefault() {
        boolean applied = new TesterDiagnosticsMode(() -> "0.1.0-alpha.2-tester").apply();

        assertTrue(applied);
        assertEquals("true", System.getProperty("lumi.loadLog"));
        assertEquals("true", System.getProperty("lumi.clientLoadLog"));
        assertEquals("true", System.getProperty("lumi.lightLog"));
        assertEquals("true", System.getProperty("lumi.blockApplyLog"));
        assertEquals("true", System.getProperty("lumi.partialRestoreLog"));
        assertEquals("25", System.getProperty("lumi.loadLog.slowMs"));
        assertEquals("30", System.getProperty("lumi.loadLog.summarySeconds"));
        assertEquals("20", System.getProperty("lumi.loadLog.top"));
        assertEquals("20", System.getProperty("lumi.clientLoadLog.sampleTicks"));
        assertEquals("10", System.getProperty("lumi.clientLoadLog.gpuSampleSeconds"));
    }

    @Test
    void explicitDiagnosticsFlagsAreNotOverwritten() {
        System.setProperty("lumi.loadLog", "false");
        System.setProperty("lumi.loadLog.slowMs", "100");
        System.setProperty("lumi.clientLoadLog.sampleTicks", "60");

        boolean applied = new TesterDiagnosticsMode(() -> "0.1.0-alpha.2-tester").apply();

        assertTrue(applied);
        assertEquals("false", System.getProperty("lumi.loadLog"));
        assertEquals("100", System.getProperty("lumi.loadLog.slowMs"));
        assertEquals("60", System.getProperty("lumi.clientLoadLog.sampleTicks"));
        assertEquals("true", System.getProperty("lumi.clientLoadLog"));
        assertEquals("true", System.getProperty("lumi.blockApplyLog"));
    }

    @Test
    void regularVersionDoesNotEnableDiagnosticsWithoutFlag() {
        boolean applied = new TesterDiagnosticsMode(() -> "0.1.0-alpha.2").apply();

        assertFalse(applied);
        assertNull(System.getProperty("lumi.loadLog"));
        assertNull(System.getProperty("lumi.clientLoadLog"));
    }

    @Test
    void systemFlagEnablesDiagnosticsForRegularBuild() {
        System.setProperty("lumi.testerDiagnostics", "true");

        boolean applied = new TesterDiagnosticsMode(() -> "0.1.0-alpha.2").apply();

        assertTrue(applied);
        assertEquals("true", System.getProperty("lumi.loadLog"));
        assertEquals("true", System.getProperty("lumi.clientLoadLog"));
    }
}
