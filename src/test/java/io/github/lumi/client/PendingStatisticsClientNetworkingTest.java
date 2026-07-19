package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PendingStatisticsClientNetworkingTest {
    @Test
    void registersCorrelatesAndSendsTheTypedRequest() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClientNetworking.java"));

        assertTrue(source.contains(
                "PendingStatisticsPayload.TYPE, (payload, context) ->"));
        assertTrue(source.contains("pendingStatistics.accept(payload)"));
        assertTrue(source.contains(
                "pendingStatistics.begin(requestId, snapshot)"));
        assertTrue(source.contains(
                "new PendingStatisticsRequestPayload("));
        assertTrue(source.contains("pendingStatistics.clear()"));
    }
}
