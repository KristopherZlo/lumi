package io.github.lumi.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.lumi.domain.model.HudDisplayMode;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LumiServerNetworkingTest {
    @Test
    void createsNativeBossBarsOnlyForBossbarMode() {
        assertFalse(LumiServerNetworking.usesBossBar(HudDisplayMode.GUI));
        assertTrue(LumiServerNetworking.usesBossBar(HudDisplayMode.BOSSBAR));
        assertFalse(LumiServerNetworking.usesBossBar(HudDisplayMode.NONE));
    }

    @Test
    void resolvesHudModeBeforeStartingTrackedWork() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));
        int start = source.indexOf("start(player, runtime, actual, payload)");
        int mode = source.lastIndexOf("HudDisplayMode hudDisplayMode", start);

        assertTrue(mode >= 0 && mode < start);
    }

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

    @Test
    void refreshesPendingHudAfterBuilderChangesSettle() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));

        assertTrue(source.contains("ServerTickEvents.END_SERVER_TICK"));
        assertTrue(source.contains("runtime.pendingRevision()"));
        assertTrue(source.contains("PENDING_REFRESH_TICKS"));
    }

    @Test
    void reportsTheActualBranchSwitchOutcome() {
        assertEquals("luma.status.variant_switched",
                LumiServerNetworking.branchSwitchMessage(
                        HistoryCommandPayload.Kind.BRANCH_SWITCH,
                        OperationEventPayload.State.SUCCEEDED,
                        "Operation completed"));
        assertEquals("luma.status.variant_switch_requires_saved_draft",
                LumiServerNetworking.branchSwitchMessage(
                        HistoryCommandPayload.Kind.BRANCH_SWITCH,
                        OperationEventPayload.State.FAILED,
                        "Branch switch requires no pending builder changes"));
    }

    @Test
    void reportsFailuresEvenWhenTheDimensionRuntimeIsUnavailable() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/io/github/lumi/network/LumiServerNetworking.java"));
        int reject = source.indexOf("private static void reject(");
        int sendEvent = source.indexOf("private static void sendEvent(", reject);

        assertTrue(source.substring(reject, sendEvent)
                .contains("notifyFailure(player, message)"));
        assertTrue(source.contains("luma.status.dimension_not_ready"));
        assertTrue(source.contains("luma.status.operation_feedback_failed"));
    }
}
