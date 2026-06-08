package io.github.luma.telemetry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetrySanitizerTest {

    private final TelemetrySanitizer sanitizer = new TelemetrySanitizer();

    @Test
    void stripsPathsCoordinatesWorldNamesSeedsAndUuidLikeValues() {
        String sanitized = this.sanitizer.sanitizeText(
                "Failed in C:\\Users\\Alex\\AppData\\Roaming\\.minecraft\\saves\\Castle World\\region\\r.1.2.mca "
                        + "at /home/alex/.minecraft/saves/Test World/region/r.3.4.mca "
                        + "BlockPos{x=123, y=64, z=-456} seed=123456789 "
                        + "player=550e8400-e29b-41d4-a716-446655440000"
        );

        assertFalse(sanitized.contains("Alex"));
        assertFalse(sanitized.contains("alex"));
        assertFalse(sanitized.contains("Castle World"));
        assertFalse(sanitized.contains("Test World"));
        assertFalse(sanitized.contains("123456789"));
        assertFalse(sanitized.contains("550e8400"));
        assertFalse(sanitized.contains("x=123"));
        assertTrue(sanitized.contains("<path>"));
        assertTrue(sanitized.contains("<pos>"));
        assertTrue(sanitized.contains("seed=<redacted>"));
    }
}
