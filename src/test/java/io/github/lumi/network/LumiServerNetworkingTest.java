package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiServerNetworkingTest {
    @Test
    void keepsJoinSnapshotBoundedOnTheServerThread() throws Exception {
        String networking = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));
        String factory = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/HistorySnapshotFactory.java"));
        int join = networking.indexOf("ServerPlayConnectionEvents.JOIN.register");
        int disconnect = networking.indexOf(
                "ServerPlayConnectionEvents.DISCONNECT.register", join);

        assertTrue(networking.substring(join, disconnect).contains("sendSnapshot("));
        assertFalse(networking.contains("sendInitialSnapshot("));
        assertFalse(factory.contains("runtime.history("));
        assertTrue(factory.contains("runtime.zoneHistories("));
        assertTrue(factory.contains("activeZoneIds"));
        assertTrue(factory.contains("1);"));
    }

    @Test
    void defersTerminalSnapshotUntilCoordinatorReleasesOwnership() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));
        int terminalStart = source.indexOf("private static void terminal(");
        int terminalEnd = source.indexOf("private static void cancel(", terminalStart);
        String terminal = source.substring(terminalStart, terminalEnd);

        assertTrue(terminal.contains("deferSnapshotBroadcast(runtime);"));
        assertTrue(terminal.contains("previewBounds(operation)"));
        assertFalse(terminal.contains("broadcastSnapshot(runtime);"));
        assertTrue(source.contains("server.schedule(new TickTask("));
    }
}
