package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QuickRollbackArgumentTest {
    @Test
    void roundTripsOnlyWholeWorkspaceScope() {
        var whole = new QuickRollbackArgument();
        assertEquals(whole, QuickRollbackArgument.parse(whole.encode()));

        assertThrows(IllegalArgumentException.class,
                () -> QuickRollbackArgument.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> QuickRollbackArgument.parse("1,2,3,4,5,6"));
    }
}
