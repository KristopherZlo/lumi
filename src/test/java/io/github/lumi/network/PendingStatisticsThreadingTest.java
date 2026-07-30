package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PendingStatisticsThreadingTest {
    @Test
    void queuesIncrementalCaptureInsteadOfReadingTheWorldInTheHandler()
            throws Exception {
        String handler = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/"
                        + "PendingStatisticsCommandHandler.java"));
        String operation = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/operation/"
                        + "PendingStatisticsOperation.java"));
        String runtime = Files.readString(Path.of(
                "src/main/java/io/github/lumi/minecraft/runtime/"
                        + "FabricDimensionRuntime.java"));

        assertTrue(handler.contains("runtime.startPendingStatistics("));
        assertTrue(handler.contains(
                "outcome.terminalState() == MutationTerminalState.CANCELLED"));
        assertTrue(!handler.contains("reader.read("));
        assertTrue(operation.indexOf("reader.read(key)")
                < operation.indexOf("CompletableFuture.supplyAsync"));
        assertTrue(runtime.contains("private void enqueueForeground("));
        assertTrue(runtime.contains("operations.cancel(ticket.orElseThrow())"));
    }
}
