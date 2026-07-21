package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiServerNetworkingTest {
    @Test
    void preparesInitialHistorySnapshotOffTheServerThread() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));
        int join = source.indexOf("ServerPlayConnectionEvents.JOIN.register");
        int disconnect = source.indexOf("ServerPlayConnectionEvents.DISCONNECT.register", join);

        assertTrue(source.substring(join, disconnect).contains("sendInitialSnapshot("));
        assertTrue(source.contains("CompletableFuture.supplyAsync("));
        assertTrue(source.contains("runtime.backgroundExecutor()"));
        assertTrue(source.contains("runtime.level().getServer().execute("));
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
