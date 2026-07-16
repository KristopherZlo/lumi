package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class MergeArgumentTest {
    @Test
    void roundTripsVisibleBranchNameAndMessage() {
        MergeArgument argument = new MergeArgument(
                "workspace/clock/fast idea", "Merge fast idea");

        assertEquals(argument, MergeArgument.parse(argument.encode()));
    }

    @Test
    void rejectsBlankOrMalformedValues() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeArgument(" ", "Merge"));
        assertThrows(IllegalArgumentException.class,
                () -> MergeArgument.parse("missing separator"));
    }
}
