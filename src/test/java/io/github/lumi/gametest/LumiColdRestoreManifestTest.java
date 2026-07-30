package io.github.lumi.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.domain.model.BlockBox;
import io.github.lumi.domain.model.BranchName;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LumiColdRestoreManifestTest {
    @Test
    void roundTripsFixtureIdentity(@TempDir Path temporary) throws Exception {
        LumiColdRestoreManifest expected = new LumiColdRestoreManifest(
                "fixture",
                new BranchName("initial"),
                commit('a'),
                new BranchName("latest"),
                commit('b'),
                new BlockBox(-8, 10, -7, 16, 25, 18),
                "initial-digest",
                "latest-digest",
                "fixture-digest");
        Path manifest = temporary.resolve("fixture.properties");

        expected.write(manifest);

        assertEquals(expected, LumiColdRestoreManifest.read(manifest));
    }

    private static CommitId commit(char digit) {
        return new CommitId(new ObjectId(
                String.valueOf(digit).repeat(ObjectId.HEX_LENGTH)));
    }
}
