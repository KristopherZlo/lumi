package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneDeleteArgumentTest {
    @Test
    void roundTripsIdentityAndRevision() {
        var argument = new ZoneDeleteArgument(new UUID(1, 2), 42);

        assertEquals(argument, ZoneDeleteArgument.parse(argument.encode()));
    }

    @Test
    void rejectsMalformedOrNegativeRevisions() {
        assertThrows(IllegalArgumentException.class,
                () -> new ZoneDeleteArgument(new UUID(1, 2), -1));
        assertThrows(IllegalArgumentException.class,
                () -> ZoneDeleteArgument.parse("not-a-zone\n2"));
    }
}
