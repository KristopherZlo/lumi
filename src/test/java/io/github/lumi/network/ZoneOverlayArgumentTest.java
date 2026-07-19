package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ZoneOverlayArgumentTest {
    @Test
    void roundTripsBothLegacyModes() {
        for (ZoneOverlayArgument.Mode mode
                : ZoneOverlayArgument.Mode.values()) {
            var argument = new ZoneOverlayArgument(mode);
            assertEquals(
                    argument, ZoneOverlayArgument.parse(argument.encode()));
        }
    }

    @Test
    void rejectsHiddenAsAQueryMode() {
        assertThrows(IllegalArgumentException.class,
                () -> ZoneOverlayArgument.parse("hidden"));
    }
}
