package io.github.lumi.client.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.lumi.domain.model.CommitId;
import io.github.lumi.domain.model.ObjectId;
import io.github.lumi.network.HistorySnapshotPayload;
import io.github.lumi.network.OperationEventPayload;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientHistoryStoreTest {
    @Test
    void appliesCorrelatedEventsWithoutMutatingServerOwnedHistory() {
        ClientHistoryStore store = new ClientHistoryStore();
        store.accept(new HistorySnapshotPayload(
                "minecraft:overworld", id('1'), 3, 2, false));
        UUID request = UUID.fromString("10000000-0000-0000-0000-000000000001");

        store.accept(new OperationEventPayload(
                request, "minecraft:overworld", OperationEventPayload.State.ACCEPTED,
                "Accepted", id('1'), 3));
        store.accept(new OperationEventPayload(
                request, "minecraft:overworld", OperationEventPayload.State.SUCCEEDED,
                "Complete", id('2'), 4));

        ClientHistoryState state = store.state();
        assertEquals(id('2'), state.snapshot().orElseThrow().head());
        assertEquals(4, state.snapshot().orElseThrow().revision());
        assertFalse(state.snapshot().orElseThrow().operationActive());
        assertEquals(OperationEventPayload.State.SUCCEEDED,
                state.events().get(request).state());
    }

    @Test
    void dimensionSnapshotDropsEventsFromThePreviousDimension() {
        ClientHistoryStore store = new ClientHistoryStore();
        UUID request = UUID.randomUUID();
        store.accept(new HistorySnapshotPayload("minecraft:overworld", id('1'), 0, 0, false));
        store.accept(new OperationEventPayload(
                request, "minecraft:overworld", OperationEventPayload.State.FAILED,
                "Failed", id('1'), 0));

        store.accept(new HistorySnapshotPayload("minecraft:the_nether", id('2'), 0, 0, false));

        assertEquals("minecraft:the_nether",
                store.state().snapshot().orElseThrow().dimensionId());
        assertEquals(0, store.state().events().size());
    }

    @Test
    void oneTerminalEventDoesNotHideAnotherAcceptedOperation() {
        ClientHistoryStore store = new ClientHistoryStore();
        store.accept(new HistorySnapshotPayload(
                "minecraft:overworld", id('1'), 0, 0, false));
        UUID first = new UUID(0, 1);
        UUID second = new UUID(0, 2);
        store.accept(new OperationEventPayload(
                first, "minecraft:overworld", OperationEventPayload.State.ACCEPTED,
                "Active", id('1'), 0));
        store.accept(new OperationEventPayload(
                second, "minecraft:overworld", OperationEventPayload.State.ACCEPTED,
                "Queued", id('1'), 0));
        store.accept(new OperationEventPayload(
                first, "minecraft:overworld", OperationEventPayload.State.SUCCEEDED,
                "Done", id('2'), 1));

        assertEquals(true, store.state().snapshot().orElseThrow().operationActive());
    }

    private static CommitId id(char digit) {
        return new CommitId(new ObjectId(String.valueOf(digit).repeat(64)));
    }
}
