package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiDeleteVersionScreenTest {
    @Test
    void waitsForTheCorrelatedServerResultBeforeClosing() throws Exception {
        String screen = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/ui/LumiDeleteVersionScreen.java"));
        String client = Files.readString(Path.of(
                "src/main/java/io/github/lumi/client/LumiClient.java"));

        assertTrue(screen.contains("requestId = delete.apply(version.id())"));
        assertTrue(screen.contains("requestId.equals(event.requestId())"));
        assertTrue(screen.contains("OperationEventPayload.State.SUCCEEDED"));
        assertTrue(screen.contains("OperationEventPayload.State.FAILED"));
        assertTrue(client.contains("HISTORY_PAGES.invalidateDimension"));
        assertTrue(client.contains("NETWORKING.refreshSnapshot()"));
    }
}
