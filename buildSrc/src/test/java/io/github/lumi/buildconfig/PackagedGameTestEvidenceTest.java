package io.github.lumi.buildconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedGameTestEvidenceTest {
    @TempDir Path directory;

    @Test
    void certifiesOnlyNonemptyPassingTestsOfAnUnchangedArtifact() throws Exception {
        var evidence = new PackagedGameTestEvidence();
        Path artifact = directory.resolve("lumi.jar");
        Path report = directory.resolve("tests.xml");
        Files.writeString(artifact, "candidate");
        String hash = evidence.hash(artifact);
        Files.writeString(report, "<testsuite><testcase name=\"exact\"/></testsuite>");
        assertEquals(1, evidence.verify(report, artifact, hash));
        for (String invalid : new String[] {"<testsuite/>", "<testsuite><testcase><failure/></testcase></testsuite>",
                "<testsuite><testcase><skipped/></testcase></testsuite>", "<testsuite"}) {
            Files.writeString(report, invalid);
            assertThrows(IOException.class, () -> evidence.verify(report, artifact, hash));
        }
        Files.writeString(report, "<testsuite><testcase/></testsuite>");
        Files.writeString(artifact, "changed");
        assertThrows(IOException.class, () -> evidence.verify(report, artifact, hash));
    }
}
