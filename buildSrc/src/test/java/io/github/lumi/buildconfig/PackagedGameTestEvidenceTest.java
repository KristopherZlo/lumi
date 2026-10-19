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

    @Test void allMustActuallyReachExactAndRecoveryAssertions() throws Exception {
        var evidence = new PackagedGameTestEvidence();
        Path completion = directory.resolve("client.completed");
        Path log = directory.resolve("logs/latest.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "Behavior gate exact_restore: succeeded (0 ticks)\n"
                + "Behavior gate exact_restore_reopen: succeeded (0 ticks)\n");
        assertEquals(2, evidence.verifyClientCases(completion, "EXACT").size());
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "ALL"));
        Files.writeString(log, "Behavior gate recovery_reopen: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(3, evidence.verifyClientCases(completion, "ALL").size());
        Files.delete(log);
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "ALL"));
    }

    @Test void clientRequiresCompletionOfTheSelectedSuiteAndUnchangedArtifact() throws Exception {
        var evidence = new PackagedGameTestEvidence();
        Path artifact = directory.resolve("lumi.jar");
        Path completion = directory.resolve("client.completed");
        Files.writeString(artifact, "candidate");
        String hash = evidence.hash(artifact);
        Files.writeString(completion, "EXACT\n" + hash);
        evidence.verifyClient(completion, artifact, hash, "EXACT");
        assertThrows(IOException.class, () -> evidence.verifyClient(completion, artifact, hash, "SMOKE"));
        Files.writeString(completion, "EXACT\nwrong");
        assertThrows(IOException.class, () -> evidence.verifyClient(completion, artifact, hash, "EXACT"));
        Files.writeString(completion, "EXACT\n" + hash);
        Files.writeString(artifact, "changed");
        assertThrows(IOException.class, () -> evidence.verifyClient(completion, artifact, hash, "EXACT"));
    }

    @Test void axiomToolEvidenceRequiresNativeHistoryWithoutRepeatingTheEditorSuite() throws Exception {
        var evidence = new PackagedGameTestEvidence();
        Path completion = directory.resolve("client.completed");
        Path log = directory.resolve("logs/latest.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "Behavior gate builder_tools_reopen: succeeded (0 ticks)\n");
        assertEquals(1, evidence.verifyClientCases(completion, "BUILDER_TOOLS", "worldedit").size());
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "BUILDER_TOOLS", "axiom"));
        Files.writeString(log, "Behavior gate axiom_integrated_biomes: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "BUILDER_TOOLS", "both"));
        Files.writeString(log, "Behavior gate axiom_entity_client_history: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "BUILDER_TOOLS", "axiom"));
        Files.writeString(log, "Behavior gate axiom_pending_work: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "BUILDER_TOOLS", "axiom"));
        Files.writeString(log, "Behavior gate axiom_split_dispatch: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(5, evidence.verifyClientCases(completion, "BUILDER_TOOLS", "axiom").size());
        Files.writeString(log, "Behavior gate axiom_editor_ui: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "AXIOM_EDITOR", "axiom"));
        Files.writeString(log, "Behavior gate axiom_editor_depth: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(2, evidence.verifyClientCases(completion, "AXIOM_EDITOR", "axiom").size());
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "ALL", "both"));
    }

    @Test void focusedEditorEvidenceCannotSubstituteForToolHistory() throws Exception {
        var evidence = new PackagedGameTestEvidence();
        Path completion = directory.resolve("client.completed");
        Path log = directory.resolve("logs/latest.log");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "Behavior gate builder_tools_reopen: succeeded (0 ticks)\n");
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "AXIOM_EDITOR", "axiom"));
        Files.writeString(log, "Behavior gate axiom_editor_ui: succeeded (0 ticks)\n");
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "AXIOM_EDITOR", "axiom"));
        Files.writeString(log, "Behavior gate axiom_editor_depth: succeeded (0 ticks)\n",
                java.nio.file.StandardOpenOption.APPEND);
        assertEquals(2, evidence.verifyClientCases(completion, "AXIOM_EDITOR", "axiom").size());
        assertThrows(IOException.class, () -> evidence.verifyClientCases(completion, "BUILDER_TOOLS", "axiom"));
    }

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
