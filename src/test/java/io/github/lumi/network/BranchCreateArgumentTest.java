package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import org.junit.jupiter.api.Test;

class BranchCreateArgumentTest {
    @Test
    void roundTripsAnExistingSaveAndBranchName() {
        BranchCreateArgument argument = new BranchCreateArgument(
                new BranchName("clock idea"), commit('a'));

        assertEquals(argument, BranchCreateArgument.parse(argument.encode()));
    }

    @Test
    void rejectsMissingOrMultipleSeparators() {
        assertThrows(IllegalArgumentException.class,
                () -> BranchCreateArgument.parse("idea"));
        assertThrows(IllegalArgumentException.class,
                () -> BranchCreateArgument.parse(commit('a').hex() + "\nidea\nother"));
    }

    private static CommitId commit(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
