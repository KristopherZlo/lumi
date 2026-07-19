package io.github.lumi.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DimensionHistoryNetworkingTest {
    @Test
    void sendsTheExplicitReadOnlyDimensionScope() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClientNetworking.java"));

        assertTrue(source.contains("requestDimensionHistoryPage("));
        assertTrue(source.contains("HistoryPageRequestPayload.ACTIVE_WORKSPACE"));
        assertTrue(source.contains("HistoryPageRequestPayload.ACTIVE_BRANCH"));
        assertTrue(source.contains("requestId, dimensionId, workspaceId"));
    }
}
