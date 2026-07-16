package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockBox;
import org.junit.jupiter.api.Test;

class ZoneCreateArgumentTest {
    @Test
    void roundTripsNameAndNormalizedSelection() {
        ZoneCreateArgument argument = new ZoneCreateArgument(
                "Clock", new BlockBox(8, 9, 10, 2, 3, 4));

        assertEquals(argument, ZoneCreateArgument.parse(argument.encode()));
        assertEquals(new BlockBox(2, 3, 4, 8, 9, 10), argument.area());
    }

    @Test
    void rejectsBlankOrMalformedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneCreateArgument(" ", new BlockBox(0, 0, 0, 0, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> ZoneCreateArgument.parse("Clock\n1,2"));
    }
}
