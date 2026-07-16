package io.github.lumi.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

class TelemetryPrivacyTest {
    @Test
    void keepsOnlyBoundedTechnicalFields() {
        var raw = new LinkedHashMap<String, String>();
        raw.put("operation", "Restore\nsecret");
        raw.put("failureClass", "java.io.IOException");
        raw.put("worldName", "private-world");
        raw.put("coordinates", "10,64,10");

        var safe = TelemetryPrivacy.sanitize(raw);

        assertEquals("Restore secret", safe.get("operation"));
        assertEquals("java.io.IOException", safe.get("failureClass"));
        assertFalse(safe.containsKey("worldName"));
        assertFalse(safe.containsKey("coordinates"));
    }
}
