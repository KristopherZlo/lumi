package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HistoryPageThreadingTest {
    @Test
    void materializesVersionSidecarsBeforeReturningToServerThread() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/HistoryPageCommandHandler.java"));

        int materialize = source.indexOf("HistoryPagePayload result");
        int serverThread = source.indexOf("context.server().execute");
        assertTrue(materialize >= 0 && materialize < serverThread);
        assertTrue(source.contains("request.browsesDimension()"));
        assertTrue(source.contains("runtime.activeRef().name()"));
    }
}
