package io.github.lumi.client.ui;

import static org.junit.jupiter.api.Assertions.*;

import io.github.lumi.client.state.ClientHistoryStore;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ZoneEntryControllerTest {
    private static final UUID WORKSPACE = new UUID(0, 1);
    private static final UUID ZONE = new UUID(0, 2);
    private static final UUID REQUEST = new UUID(0, 3);
    private static final CommitId HEAD = CommitId.hash(new byte[] {1});

    @Test
    void terminalRefusalReleasesTheWaitAndAllowsRetry() {
        for (var terminal : List.of(OperationEventPayload.State.FAILED,
                OperationEventPayload.State.CANCELLED, OperationEventPayload.State.DEGRADED)) {
            var history = new ClientHistoryStore();
            history.accept(snapshot(false, WORKSPACE));
            var requests = new java.util.ArrayDeque<>(List.of(REQUEST, new UUID(0, 9)));
            var entry = new ZoneEntryController(ignored -> requests.removeFirst());
            entry.begin(history.state().snapshot().orElseThrow(), ZONE);
            assertTrue(entry.pending());
            assertFalse(entry.poll(history.state()).changed());
            history.accept(event(REQUEST, terminal, "Busy"));

            var update = entry.poll(history.state());

            assertTrue(update.changed());
            assertEquals("Busy", update.error());
            assertTrue(update.entered().isEmpty());
            assertFalse(entry.pending());
            entry.begin(history.state().snapshot().orElseThrow(), ZONE);
            assertTrue(entry.pending());
        }
    }

    @Test
    void unrelatedFailureAndSuccessWithoutActivationKeepWaiting() {
        var history = new ClientHistoryStore();
        history.accept(snapshot(false, WORKSPACE));
        var entry = new ZoneEntryController(ignored -> REQUEST);
        entry.begin(history.state().snapshot().orElseThrow(), ZONE);
        history.accept(event(new UUID(0, 4), OperationEventPayload.State.FAILED, "Other"));
        assertFalse(entry.poll(history.state()).changed());
        history.accept(snapshot(true, WORKSPACE));
        assertTrue(entry.poll(history.state()).entered().isEmpty());
        history.accept(snapshot(false, WORKSPACE));
        history.accept(event(REQUEST, OperationEventPayload.State.SUCCEEDED, "Entered"));
        assertTrue(entry.poll(history.state()).entered().isEmpty());
        assertTrue(entry.pending());

        history.accept(snapshot(true, WORKSPACE));

        assertEquals(ZONE, entry.poll(history.state()).entered().orElseThrow().id());
        assertFalse(entry.pending());
    }

    @Test
    void disconnectOrWorkspaceChangeReleasesTheWait() {
        var history = new ClientHistoryStore();
        history.accept(snapshot(false, WORKSPACE));
        var entry = new ZoneEntryController(ignored -> REQUEST);
        entry.begin(history.state().snapshot().orElseThrow(), ZONE);
        history.clear();
        assertTrue(entry.poll(history.state()).changed());
        assertFalse(entry.pending());
        history.accept(snapshot(false, WORKSPACE));
        entry.begin(history.state().snapshot().orElseThrow(), ZONE);
        history.accept(snapshot(false, new UUID(0, 8)));
        assertTrue(entry.poll(history.state()).changed());
        assertFalse(entry.pending());
    }

    @Test
    void localSendFailureDoesNotLockTheScreen() {
        var entry = new ZoneEntryController(ignored -> { throw new IllegalStateException("Offline"); });
        assertThrows(IllegalStateException.class, () -> entry.begin(snapshot(false, WORKSPACE), ZONE));
        assertFalse(entry.pending());
    }

    private static HistorySnapshotPayload snapshot(boolean active, UUID workspace) {
        return new HistorySnapshotPayload("minecraft:overworld", HEAD, 0, 0,
                false, false, workspace, "Build", "main", List.of(), List.of(),
                List.of(), List.of(new HistorySnapshotPayload.ZoneView(
                        ZONE, "Zone", 0, 1, 0L, active)), List.of());
    }

    private static OperationEventPayload event(UUID request, OperationEventPayload.State state, String text) {
        return new OperationEventPayload(request, "minecraft:overworld", state, text, HEAD, 0);
    }
}
