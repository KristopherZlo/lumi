package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ZoneCreateArgumentTest {
    @Test
    void roundTripsTrimmedNameWithoutASelection() {
        ZoneCreateArgument argument = new ZoneCreateArgument("  Clock  ");

        assertEquals(argument, ZoneCreateArgument.parse(argument.encode()));
        assertEquals("Clock", argument.name());
    }

    @Test
    void rejectsBlankOrMalformedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneCreateArgument(" "));
        assertThrows(IllegalArgumentException.class,
                () -> ZoneCreateArgument.parse("Clock\ncell"));
    }
}
