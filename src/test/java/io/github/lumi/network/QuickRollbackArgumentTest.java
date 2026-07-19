package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockBox;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuickRollbackArgumentTest {
    @Test
    void roundTripsWholeAndNormalizedSelectedScopes() {
        var whole = new QuickRollbackArgument(Optional.empty());
        assertEquals(whole, QuickRollbackArgument.parse(whole.encode()));

        var selected = new QuickRollbackArgument(Optional.of(
                new BlockBox(16, 5, 8, -2, -3, 4)));
        assertEquals(new BlockBox(-2, -3, 4, 16, 5, 8),
                QuickRollbackArgument.parse(selected.encode())
                        .selection().orElseThrow());
    }

    @Test
    void rejectsMissingAndNonNumericCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> QuickRollbackArgument.parse(""));
        assertThrows(IllegalArgumentException.class,
                () -> QuickRollbackArgument.parse("1,2,3,4,5,nope"));
    }
}
