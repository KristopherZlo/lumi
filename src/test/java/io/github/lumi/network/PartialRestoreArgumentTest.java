package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BlockAreaTarget;
import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import org.junit.jupiter.api.Test;

class PartialRestoreArgumentTest {
    @Test
    void roundTripsNormalizedBoundsAndOutsideMode() {
        var argument = new PartialRestoreArgument(
                id('1'), new BlockAreaTarget(new BlockBox(8, 9, 10, 2, 3, 4), true));

        assertEquals(argument, PartialRestoreArgument.parse(argument.encode()));
        assertEquals(new BlockBox(2, 3, 4, 8, 9, 10), argument.area().area());
    }

    @Test
    void rejectsMalformedOrNonBooleanPayload() {
        assertThrows(IllegalArgumentException.class,
                () -> PartialRestoreArgument.parse("broken"));
        assertThrows(IllegalArgumentException.class,
                () -> PartialRestoreArgument.parse(
                        id('1').hex() + "|1|2|3|4|5|6|maybe"));
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
