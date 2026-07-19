package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiServerNetworkingTest {
    @Test
    void defersTerminalSnapshotUntilCoordinatorReleasesOwnership() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));
        int terminalStart = source.indexOf("private static void terminal(");
        int terminalEnd = source.indexOf("private static void cancel(", terminalStart);
        String terminal = source.substring(terminalStart, terminalEnd);

        assertTrue(terminal.contains("deferSnapshotBroadcast(runtime);"));
        assertFalse(terminal.contains("broadcastSnapshot(runtime);"));
        assertTrue(source.contains("server.schedule(new TickTask("));
    }
}
