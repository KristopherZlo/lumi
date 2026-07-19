package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockBox;
import org.junit.jupiter.api.Test;

class ZoneCellsArgumentTest {
    @Test
    void roundTripsAddAndNormalizedArea() {
        var argument = new ZoneCellsArgument(
                true, new BlockBox(31, 15, -1, -16, 0, -17));

        assertEquals(argument, ZoneCellsArgument.parse(argument.encode()));
        assertEquals(new BlockBox(-16, 0, -17, 31, 15, -1), argument.area());
    }

    @Test
    void rejectsUnknownActionsAndMalformedAreas() {
        assertThrows(IllegalArgumentException.class,
                () -> ZoneCellsArgument.parse("toggle\n0,0,0,1,1,1"));
        assertThrows(IllegalArgumentException.class,
                () -> ZoneCellsArgument.parse("add\n0,0"));
    }
}
