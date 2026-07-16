package io.github.lumi.client.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.lumi.client.state.ClientHistoryState;
import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientDiagnosticsTest {
    @Test
    void summarizesOnlyTheImmutableClientSnapshot() {
        CommitId head = new CommitId(new ObjectId("1".repeat(64)));
        var snapshot = new HistorySnapshotPayload(
                "minecraft:overworld", head, 4, 7, true, false,
                UUID.randomUUID(), "Castle", "workspace/main",
                List.of(), List.of(), List.of(), List.of());

        ClientDiagnostics diagnostics = ClientDiagnostics.from(
                new ClientHistoryState(Optional.of(snapshot), Map.of()),
                true, false, 128);

        assertEquals("minecraft:overworld", diagnostics.dimension());
        assertEquals("Castle", diagnostics.workspace());
        assertEquals("main", diagnostics.branch());
        assertEquals(7, diagnostics.pendingKeys());
        assertEquals("active", diagnostics.operation());
        assertEquals("available", diagnostics.worldEdit());
        assertEquals("unavailable", diagnostics.axiom());
    }
}
